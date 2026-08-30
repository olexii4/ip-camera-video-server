package com.ipcamera.videoserver.server

import com.ipcamera.videoserver.archive.ArchiveManager
import com.ipcamera.videoserver.auth.AuthManager
import com.ipcamera.videoserver.auth.SessionInfo
import com.ipcamera.videoserver.auth.SessionRegistry
import com.ipcamera.videoserver.camera.CameraSource
import com.ipcamera.videoserver.camera.CameraStreamManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

private const val BOUNDARY = "frame"
private val json = Json { ignoreUnknownKeys = true }

@Singleton
class WebServer @Inject constructor(
    private val authManager: AuthManager,
    private val sessionRegistry: SessionRegistry,
    private val cameraStreamManager: CameraStreamManager,
    private val archiveManager: ArchiveManager,
) {
    private var server: ApplicationEngine? = null

    fun start(port: Int) {
        server = embeddedServer(CIO, port = port) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(30)
            }
            routing {
                get("/ping") { call.respondText("pong") }

                get("/") { call.respondText(WEB_UI_HTML, ContentType.Text.Html) }

                // Tells the web UI whether login is required
                get("/auth-config") {
                    call.respondText(
                        """{"authRequired":${authManager.authRequired}}""",
                        ContentType.Application.Json,
                    )
                }

                post("/oauth/token") {
                    val params = call.receiveParameters()
                    val username = params["username"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing username")
                    val password = params["password"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing password")
                    val token = authManager.issueToken(username, password)
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, """{"error":"invalid_credentials"}""")
                    // Revoke all existing sessions — enforce one concurrent session
                    sessionRegistry.clearAll()
                    val claims = authManager.validateToken(token)!!
                    sessionRegistry.register(SessionInfo(claims.tokenId, claims.username, call.request.local.remoteAddress))
                    call.respondText(json.encodeToString(TokenResponse(token, "Bearer", 3600)), ContentType.Application.Json)
                }

                // WebSocket: live status + files, closes when session revoked or server stops
                webSocket("/ws") {
                    val bearer = call.request.queryParameters["token"]
                    val claims = if (authManager.authRequired) {
                        if (bearer == null) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                            return@webSocket
                        }
                        authManager.validateToken(bearer) ?: run {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                            return@webSocket
                        }
                    } else null

                    while (true) {
                        delay(2_000)
                        // Close if this session was revoked (e.g. someone else logged in)
                        if (authManager.authRequired && claims != null &&
                            sessionRegistry.activeSessions().none { it.tokenId == claims.tokenId }) {
                            close(CloseReason(CloseReason.Codes.NORMAL, "Session revoked"))
                            return@webSocket
                        }
                        val sessions = sessionRegistry.activeSessions()
                            .map { SessionDto(it.username, it.remoteAddress, it.connectedAt) }
                        val files = archiveManager.listFiles()
                            .map { FileDto(it.name, it.length(), it.lastModified()) }
                        val msg = json.encodeToString(
                            WsStatus(running = true, sessions = sessions, files = files)
                        )
                        send(Frame.Text(msg))
                    }
                }

                // Returns which camera sources are physically present on this device
                get("/cameras") {
                    requireAuth(call) ?: return@get
                    val sources = cameraStreamManager.availableSources().map { it.id }
                    call.respondText(json.encodeToString(sources), ContentType.Application.Json)
                }

                post("/logout") {
                    val bearer = extractBearer(call) ?: call.request.queryParameters["token"]
                    if (bearer != null) {
                        val claims = authManager.validateToken(bearer)
                        if (claims != null) sessionRegistry.revoke(claims.tokenId)
                    }
                    call.respond(HttpStatusCode.OK, """{"logged_out":true}""")
                }

                get("/stream/{source}") {
                    requireAuth(call) ?: return@get
                    val sourceId = call.parameters["source"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val source = CameraSource.entries.firstOrNull { it.id == sourceId }
                        ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown source: $sourceId")
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.respondBytesWriter(contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$BOUNDARY")) {
                        cameraStreamManager.getStreamExclusive(source).collect { jpegBytes ->
                            writeStringUtf8("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n")
                            writeFully(jpegBytes)
                            writeStringUtf8("\r\n")
                            flush()
                        }
                    }
                }

                get("/status") {
                    requireAuth(call) ?: return@get
                    val sessions = sessionRegistry.activeSessions().map { SessionDto(it.username, it.remoteAddress, it.connectedAt) }
                    call.respondText(json.encodeToString(StatusResponse(running = true, activeSessions = sessions)), ContentType.Application.Json)
                }

                // List archive files as JSON
                get("/files") {
                    requireAuth(call) ?: return@get
                    val files = archiveManager.listFiles().map { FileDto(it.name, it.length(), it.lastModified()) }
                    call.respondText(json.encodeToString(files), ContentType.Application.Json)
                }

                // Download a single archive file
                get("/files/download/{name}") {
                    requireAuth(call) ?: return@get
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val file = safeArchiveFile(name) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.name}\"")
                    call.respondFile(file)
                }

                // Delete a single archive file
                post("/files/delete/{name}") {
                    requireAuth(call) ?: return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val file = safeArchiveFile(name) ?: return@post call.respond(HttpStatusCode.NotFound)
                    file.delete()
                    call.respond(HttpStatusCode.OK, """{"deleted":"${file.name}"}""")
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 2_000)
        server = null
    }

    private suspend fun requireAuth(call: ApplicationCall): Unit? {
        if (!authManager.authRequired) return Unit
        val bearer = extractBearer(call) ?: call.request.queryParameters["token"]
        if (bearer == null || authManager.validateToken(bearer) == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return null
        }
        return Unit
    }

    private fun extractBearer(call: ApplicationCall): String? =
        call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.removePrefix("Bearer ")?.trim()

    private fun safeArchiveFile(name: String): File? {
        val file = File(archiveManager.archiveDir, File(name).name)
        if (!file.exists()) return null
        if (!file.canonicalPath.startsWith(archiveManager.archiveDir.canonicalPath)) return null
        return file
    }
}

@Serializable private data class TokenResponse(val access_token: String, val token_type: String, val expires_in: Int)
@Serializable private data class StatusResponse(val running: Boolean, val activeSessions: List<SessionDto>)
@Serializable private data class SessionDto(val username: String, val remoteAddress: String, val connectedAt: Long)
@Serializable private data class FileDto(val name: String, val size: Long, val modified: Long)
@Serializable private data class WsStatus(val running: Boolean, val sessions: List<SessionDto>, val files: List<FileDto>)

private val WEB_UI_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>IP Camera</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
:root{
  --bg:#0a0e14;--surface:#111827;--border:#1f2937;--border2:#374151;
  --text:#f1f5f9;--muted:#6b7280;--accent:#3b82f6;--green:#22c55e;
  --red:#ef4444;--amber:#f59e0b;
}
body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--text);min-height:100vh}

/* ── HEADER ── */
header{background:var(--surface);border-bottom:1px solid var(--border);
  padding:10px 16px;display:flex;align-items:center;gap:12px;position:sticky;top:0;z-index:100}
.logo{font-size:.95rem;font-weight:600;letter-spacing:.01em;display:flex;align-items:center;gap:6px}
.logo-dot{width:8px;height:8px;border-radius:50%;background:var(--green);box-shadow:0 0 6px var(--green)}
.logo-dot.off{background:var(--muted);box-shadow:none}
nav{display:flex;gap:4px;margin-left:auto}
.tab{background:none;border:1px solid transparent;color:var(--muted);border-radius:6px;
  padding:5px 12px;cursor:pointer;font-size:.8rem;transition:all .15s}
.tab:hover{color:var(--text);border-color:var(--border2)}
.tab.on{background:var(--accent);color:#fff;border-color:var(--accent)}
.tab.logout{color:#f87171}.tab.logout:hover{background:#1f0a0a;border-color:#7f1d1d}

/* ── PAGES ── */
.page{display:none;padding:16px}
.page.on{display:block}

/* ── LOGIN ── */
#loginWrap{max-width:320px;margin:64px auto}
.card{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:24px}
.card h2{font-size:1rem;font-weight:600;margin-bottom:18px;display:flex;align-items:center;gap:8px}
label{display:block;font-size:.78rem;color:var(--muted);margin-bottom:4px;margin-top:10px}
input[type=text],input[type=password]{
  width:100%;padding:9px 11px;background:var(--bg);border:1px solid var(--border2);
  color:var(--text);border-radius:7px;font-size:.9rem;outline:none;transition:border .15s}
input:focus{border-color:var(--accent)}
.btn{display:inline-flex;align-items:center;gap:5px;padding:8px 16px;border:none;border-radius:7px;
  cursor:pointer;font-size:.83rem;font-weight:500;transition:all .15s;text-decoration:none;white-space:nowrap}
.btn-primary{background:var(--accent);color:#fff}.btn-primary:hover{background:#2563eb}
.btn-sm{padding:5px 11px;font-size:.78rem}
.btn-danger{background:var(--red);color:#fff}.btn-danger:hover{background:#dc2626}
.btn-ghost{background:var(--border);color:var(--text)}.btn-ghost:hover{background:var(--border2)}
#loginErr{color:var(--red);font-size:.78rem;margin-top:10px;min-height:16px}
#loginBtn{width:100%;margin-top:16px;justify-content:center}

/* ── CAMERA GRID ── */
#camArea{overflow-x:hidden}
#camGrid{
  display:flex;flex-wrap:wrap;gap:10px;
  margin-bottom:10px;
}
.cam-tile{
  width:350px;flex-shrink:0;
  background:var(--surface);border:2px solid var(--border);border-radius:10px;
  overflow:hidden;cursor:pointer;transition:border-color .2s;
  position:relative;
}
.cam-tile:hover{border-color:var(--border2)}
.cam-tile.active{border-color:var(--accent)}
.cam-header{
  display:flex;align-items:center;gap:8px;padding:6px 10px;
  background:rgba(0,0,0,.55);position:absolute;top:0;left:0;right:0;z-index:2;
}
.cam-name{font-size:.76rem;font-weight:500;flex:1}
.cam-badge{font-size:.66rem;padding:2px 7px;border-radius:10px;font-weight:600}
.cam-badge.live{background:rgba(34,197,94,.18);color:var(--green)}
.cam-badge.off{background:rgba(107,114,128,.15);color:var(--muted)}
.cam-badge.usb{background:rgba(245,158,11,.15);color:var(--amber)}
.cam-expbtn{
  display:flex;align-items:center;gap:4px;background:none;border:1px solid rgba(255,255,255,.2);
  color:rgba(255,255,255,.75);border-radius:5px;padding:3px 8px;cursor:pointer;
  font-size:.68rem;transition:all .15s;
}
.cam-expbtn:hover{background:rgba(255,255,255,.1);color:#fff}
.cam-expbtn svg{width:10px;height:10px}
.cam-playbtn{
  display:flex;align-items:center;gap:4px;background:var(--accent);border:none;
  color:#fff;border-radius:5px;padding:3px 10px;cursor:pointer;
  font-size:.68rem;font-weight:500;transition:all .15s;
}
.cam-playbtn:hover{background:#2563eb}
.cam-playbtn.stop{background:rgba(239,68,68,.8)}.cam-playbtn.stop:hover{background:#ef4444}
.cam-playbtn svg{width:10px;height:10px}
.cam-frame{width:350px;height:200px;display:block;object-fit:cover;background:#050810;}
.cam-placeholder{
  width:350px;height:200px;display:flex;flex-direction:column;
  align-items:center;justify-content:center;gap:10px;
  background:#050810;color:var(--muted);
}
.cam-placeholder svg{opacity:.35}
.cam-placeholder span{font-size:.8rem;text-align:center;padding:0 16px}
.cam-placeholder .play-hint{
  display:flex;align-items:center;gap:6px;background:rgba(59,130,246,.15);
  border:1px solid rgba(59,130,246,.3);color:var(--accent);
  border-radius:20px;padding:5px 14px;font-size:.78rem;cursor:pointer;transition:all .15s;
}
.cam-placeholder .play-hint:hover{background:rgba(59,130,246,.25)}
.cam-spinner{
  width:28px;height:28px;border:2px solid var(--border2);border-top-color:var(--accent);
  border-radius:50%;animation:spin .7s linear infinite;
}
@keyframes spin{to{transform:rotate(360deg)}}
/* Expanded panel — full width row below the grid tiles */
#expandedPanel{
  display:none;width:100%;background:var(--surface);
  border:2px solid var(--accent);border-radius:10px;
  overflow:hidden;position:relative;
}
#expandedPanel img{width:100%;height:60vh;object-fit:contain;background:#050810;display:block}
#expandedPanel .exp-header{
  display:flex;align-items:center;gap:8px;padding:8px 14px;
  background:rgba(0,0,0,.6);position:absolute;top:0;left:0;right:0;z-index:2;
  font-size:.82rem;font-weight:500;
}
#expandedPanel .exp-header span{flex:1}

/* ── FILES ── */
#fileTools{display:flex;gap:8px;margin-bottom:12px;align-items:center}
#fileCount{font-size:.78rem;color:var(--muted)}
.file-list{border:1px solid var(--border);border-radius:8px;overflow:hidden}
.file-row{display:flex;align-items:center;padding:9px 12px;border-bottom:1px solid var(--border);
  gap:8px;flex-wrap:wrap;transition:background .1s}
.file-row:last-child{border-bottom:none}
.file-row:hover{background:var(--surface)}
.f-name{flex:1;font-size:.83rem;word-break:break-all;min-width:100px}
.f-meta{font-size:.73rem;color:var(--muted);white-space:nowrap}
.f-act{display:flex;gap:6px;flex-shrink:0}
.empty-state{padding:40px;text-align:center;color:var(--muted);font-size:.85rem;line-height:1.6}
</style>
</head>
<body>

<!-- LOGIN -->
<div id="loginPage">
  <div id="loginWrap">
    <div class="card">
      <h2><span>IP Camera Server</span></h2>
      <label>Username</label>
      <input id="user" type="text" value="admin" autocomplete="username">
      <label>Password</label>
      <input id="pass" type="password" value="admin" autocomplete="current-password">
      <button class="btn btn-primary" id="loginBtn" onclick="login()">Connect</button>
      <div id="loginErr"></div>
    </div>
  </div>
</div>

<!-- APP -->
<div id="appShell" style="display:none">
  <header>
    <div class="logo">
      <div class="logo-dot off" id="liveDot"></div>
      IP Camera
    </div>
    <nav>
      <button class="tab on" onclick="showTab('cameras',this)">Cameras</button>
      <button class="tab" onclick="showTab('files',this)">Files</button>
      <button class="tab logout" onclick="logout()" title="Logout"><svg width="16" height="16" viewBox="0 0 90 90" fill="currentColor"><path d="M69.313 54.442c-.397 0-.798-.118-1.147-.363-.904-.636-1.122-1.883-.487-2.786l10.118-14.399L67.679 22.495c-.635-.904-.417-2.151.487-2.786.904-.637 2.151-.417 2.786.486l10.926 15.549c.484.69.484 1.61 0 2.3L70.952 53.592c-.389.554-1.009.85-1.639.85z"/><path d="M57.693 30.092c1.104 0 2-.896 2-2V2c0-1.104-.896-2-2-2H9.759c-.037 0-.074.004-.111.007-.354.025-.639.126-.89.213-.196.05-.397.13-.523.197-.025.014-.054.019-.077.034l-.031.025a1.985 1.985 0 0 0-.36.287C8.313.62 8.299.643 8.281.662A1.985 1.985 0 0 0 7.82 1.532C7.783 1.683 7.759 1.838 7.759 2v69.787c0 .17.028.333.068.49.011.043.025.083.039.124.04.123.091.239.152.35.019.033.034.068.054.1.086.135.185.26.3.371.022.021.047.037.07.058.102.09.214.169.333.237.021.012.037.03.058.042l31.016 16.213C40.139 89.925 40.457 90 40.775 90c.359 0 .718-.097 1.036-.289.598-.362.964-1.012.964-1.711V73.787h14.918c1.104 0 2-.896 2-2V45c0-1.104-.896-2-2-2s-2 .896-2 2v24.787H42.775V18.213c0-.745-.414-1.428-1.074-1.772L17.902 4h37.791v24.092c0 1.104.896 2 2 2z"/><path d="M80.241 38.894H47.536c-1.104 0-2-.896-2-2s.896-2 2-2h32.705c1.104 0 2 .896 2 2s-.896 2-2 2z"/></svg></button>
    </nav>
  </header>

  <div id="cameras" class="page on">
    <div id="camArea">
      <div id="camGrid"></div>
      <div id="expandedPanel">
        <div class="exp-header">
          <span id="expTitle"></span>
          <button class="cam-expbtn" onclick="compressPanel()">
            <svg viewBox="0 0 448 512" fill="currentColor"><path d="M436 192H312c-13.3 0-24-10.7-24-24V44c0-6.6 5.4-12 12-12h40c6.6 0 12 5.4 12 12v84h84c6.6 0 12 5.4 12 12v40c0 6.6-5.4 12-12 12zm-276-24V44c0-6.6-5.4-12-12-12h-40c-6.6 0-12 5.4-12 12v84H12c-6.6 0-12 5.4-12 12v40c0 6.6 5.4 12 12 12h124c13.3 0 24-10.7 24-24zm0 300V344c0-13.3-10.7-24-24-24H12c-6.6 0-12 5.4-12 12v40c0 6.6 5.4 12 12 12h84v84c0 6.6 5.4 12 12 12h40c6.6 0 12-5.4 12-12zm192 0v-84h84c6.6 0 12-5.4 12-12v-40c0-6.6-5.4-12-12-12H312c-13.3 0-24 10.7-24 24v124c0 6.6 5.4 12 12 12h40c6.6 0 12-5.4 12-12z"/></svg>
            Compress
          </button>
        </div>
        <img id="expImg" alt="expanded stream">
      </div>
    </div>
  </div>

  <div id="files" class="page">
    <div id="fileTools">
      <button class="btn btn-ghost btn-sm" onclick="loadFiles()">↻ Refresh</button>
      <span id="fileCount"></span>
    </div>
    <div id="fileList"><div class="empty-state">Loading…</div></div>
  </div>
</div>

<script>
let TOKEN='', SOURCES=[], activeSource=null, expandedSource=null, switching=false, ws=null;
var intentionallyOff=new Set(); // sources stopped on purpose — suppress onerror for these

window.addEventListener('DOMContentLoaded', async ()=>{
  const cfg = await fetch('/auth-config').then(r=>r.json()).catch(()=>({authRequired:true}));
  if(!cfg.authRequired){ TOKEN=''; enterApp(); }
  document.getElementById('pass').addEventListener('keydown', e=>{ if(e.key==='Enter') login(); });
});

async function login(){
  const err=document.getElementById('loginErr'); err.textContent='';
  const btn=document.getElementById('loginBtn'); btn.textContent='Connecting…'; btn.disabled=true;
  const body=new URLSearchParams({username:document.getElementById('user').value,password:document.getElementById('pass').value});
  const r=await fetch('/oauth/token',{method:'POST',body});
  btn.textContent='Connect'; btn.disabled=false;
  if(!r.ok){err.textContent='Invalid credentials';return;}
  TOKEN=(await r.json()).access_token;
  enterApp();
}

async function enterApp(){
  document.getElementById('loginPage').style.display='none';
  document.getElementById('appShell').style.display='block';
  await loadCameras();
  connectWs();
}

function authHeader(){ return TOKEN ? {Authorization:'Bearer '+TOKEN} : {}; }

// ── WEBSOCKET ──
function connectWs(){
  if(ws){ ws.close(); ws=null; }
  const proto=location.protocol==='https:'?'wss:':'ws:';
  const url=proto+'//'+location.host+'/ws'+(TOKEN?'?token='+TOKEN:'');
  ws=new WebSocket(url);

  ws.onmessage=function(e){
    try{
      const msg=JSON.parse(e.data);
      updateStatus(msg);
      if(document.getElementById('files').classList.contains('on')) renderFiles(msg.files);
    }catch(_){}
  };

  ws.onclose=function(ev){
    ws=null;
    // Normal server stop (code 1000/1001) or session revoked (1008) → back to login
    if(activeSource){ try{document.getElementById('img_'+activeSource).src='';}catch(_){} }
    activeSource=null; expandedSource=null;
    document.getElementById('appShell').style.display='none';
    document.getElementById('loginPage').style.display='block';
    document.getElementById('loginErr').textContent=ev.code===1008?'Session revoked — another user logged in':'Server disconnected';
  };

  ws.onerror=function(){
    // will trigger onclose
  };
}

function updateStatus(msg){
  const dot=document.getElementById('liveDot');
  if(dot) dot.className='logo-dot'+(msg.running?'':' off');
}

// ── CAMERAS (unchanged) ──

// ── CAMERAS ──
async function loadCameras(){
  const r=await fetch('/cameras',{headers:authHeader()});
  SOURCES=r.ok?await r.json():['main'];
  renderGrid();
  // All cameras start in OFF state — user presses Play to start
}

var SVG_EXPAND='<svg viewBox="0 0 448 512" fill="currentColor"><path d="M0 180V56c0-13.3 10.7-24 24-24h124c6.6 0 12 5.4 12 12v40c0 6.6-5.4 12-12 12H64v84c0 6.6-5.4 12-12 12H12c-6.6 0-12-5.4-12-12zM288 44v40c0 6.6 5.4 12 12 12h84v84c0 6.6 5.4 12 12 12h40c6.6 0 12-5.4 12-12V56c0-13.3-10.7-24-24-24H300c-6.6 0-12 5.4-12 12zm148 276h-40c-6.6 0-12 5.4-12 12v84h-84c-6.6 0-12 5.4-12 12v40c0 6.6 5.4 12 12 12h124c13.3 0 24-10.7 24-24V332c0-6.6-5.4-12-12-12zM160 468v-40c0-6.6-5.4-12-12-12H64v-84c0-6.6-5.4-12-12-12H12c-6.6 0-12 5.4-12 12v124c0 13.3 10.7 24 24 24h124c6.6 0 12-5.4 12-12z"/></svg>';

// Always show all 3 slots; mark USB specially
var ALL_SOURCES = ['main','front','usb'];

function renderGrid(){
  const grid=document.getElementById('camGrid');
  var SVG_PLAY='<svg viewBox="0 0 384 512" fill="currentColor"><path d="M73 39c-14.8-9.1-33.4-9.4-48.5-.9S0 62.6 0 80V432c0 17.4 9.4 33.4 24.5 41.9s33.7 8.1 48.5-.9L361 297c14.3-8.7 23-24.2 23-41s-8.7-32.2-23-41L73 39z"/></svg>';
  var SVG_STOP='<svg viewBox="0 0 384 512" fill="currentColor"><path d="M0 128C0 92.7 28.7 64 64 64H320c35.3 0 64 28.7 64 64V384c0 35.3-28.7 64-64 64H64c-35.3 0-64-28.7-64-64V128z"/></svg>';
  grid.innerHTML=ALL_SOURCES.map(function(src){
    const label=src.charAt(0).toUpperCase()+src.slice(1)+' camera';
    const avail=SOURCES.indexOf(src)>=0;
    const badge=avail?'<span class="cam-badge off" id="badge_'+src+'">Off</span>'
                     :'<span class="cam-badge usb" id="badge_'+src+'">No device</span>';
    const playBtn=avail
      ?'<button class="cam-playbtn" id="playbtn_'+src+'" onclick="event.stopPropagation();toggleStream(\''+src+'\')">'
        +SVG_PLAY+'<span id="playbtnlabel_'+src+'">Play</span></button>'
      :'';
    const expBtn='<button class="cam-expbtn" id="expbtn_'+src+'" style="display:none" onclick="event.stopPropagation();openPanel(\''+src+'\')">'
      +SVG_EXPAND+'<span>Expand</span></button>';
    const placeholder='<div class="cam-placeholder" id="ph_'+src+'">'
      +'<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">'
      +(avail
        ?'<path d="M23 7l-7 5 7 5V7z"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>'
        :'<path d="M23 7l-7 5 7 5V7z" opacity=".3"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2" opacity=".3"/><line x1="1" y1="1" x2="23" y2="23" stroke-width="2"/>'
      )
      +'</svg>'
      +(avail
        ?'<div class="play-hint" onclick="event.stopPropagation();toggleStream(\''+src+'\')">'
          +SVG_PLAY+'<span>Play</span></div>'
        :'<span>No USB camera connected</span>'
      )
      +'</div>';
    return '<div class="cam-tile" id="tile_'+src+'">'
      +'<div class="cam-header">'
      +'<span class="cam-name">'+label+'</span>'
      +badge
      +'<div style="display:flex;gap:4px">'
      +playBtn
      +expBtn
      +'</div>'
      +'</div>'
      +placeholder
      +'<img class="cam-frame" id="img_'+src+'" style="display:none" alt="'+src+'">'
      +'</div>';
  }).join('');
}

function toggleStream(src){
  if(switching) return;
  if(SOURCES.indexOf(src)<0) return;
  if(src===activeSource) stopStream();
  else activateCamera(src);
}

function stopStream(){
  if(!activeSource) return;
  const src=activeSource;
  intentionallyOff.add(src); // mark as intentional before clearing src
  const img=document.getElementById('img_'+src);
  img.src=''; img.style.display='none';
  const ph=document.getElementById('ph_'+src);
  ph.style.display='flex';
  ph.innerHTML='<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">'
    +'<path d="M23 7l-7 5 7 5V7z"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>'
    +'</svg><div class="play-hint" onclick="event.stopPropagation();toggleStream(\''+src+'\')">'
    +SVG_PLAY+'<span>Play</span></div>';
  setBadge(src,'off','Off');
  setPlayBtn(src,false);
  document.getElementById('expbtn_'+src).style.display='none';
  document.getElementById('tile_'+src).classList.remove('active');
  if(expandedSource===src) compressPanel();
  document.getElementById('liveDot').classList.add('off');
  activeSource=null;
}

var SVG_PLAY='<svg viewBox="0 0 384 512" fill="currentColor"><path d="M73 39c-14.8-9.1-33.4-9.4-48.5-.9S0 62.6 0 80V432c0 17.4 9.4 33.4 24.5 41.9s33.7 8.1 48.5-.9L361 297c14.3-8.7 23-24.2 23-41s-8.7-32.2-23-41L73 39z"/></svg>';
var SVG_STOP_SM='<svg viewBox="0 0 384 512" fill="currentColor"><path d="M0 128C0 92.7 28.7 64 64 64H320c35.3 0 64 28.7 64 64V384c0 35.3-28.7 64-64 64H64c-35.3 0-64-28.7-64-64V128z"/></svg>';

function setPlayBtn(src,playing){
  const btn=document.getElementById('playbtn_'+src);
  const lbl=document.getElementById('playbtnlabel_'+src);
  if(!btn) return;
  if(playing){
    btn.className='cam-playbtn stop';
    btn.innerHTML=SVG_STOP_SM+'<span id="playbtnlabel_'+src+'">Stop</span>';
  } else {
    btn.className='cam-playbtn';
    btn.innerHTML=SVG_PLAY+'<span id="playbtnlabel_'+src+'">Play</span>';
  }
}

function handleTileClick(src){}

async function activateCamera(src){
  if(switching) return;
  switching=true;

  // Stop old stream
  if(activeSource){
    const prev=activeSource;
    intentionallyOff.add(prev); // mark before clearing src to suppress onerror
    const oldImg=document.getElementById('img_'+prev);
    oldImg.src=''; oldImg.style.display='none';
    const prevPh=document.getElementById('ph_'+prev);
    prevPh.style.display='flex';
    prevPh.innerHTML='<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">'
      +'<path d="M23 7l-7 5 7 5V7z"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>'
      +'</svg><div class="play-hint" onclick="event.stopPropagation();toggleStream(\''+prev+'\')">'
      +SVG_PLAY+'<span>Play</span></div>';
    setBadge(prev,'off','Off');
    setPlayBtn(prev,false);
    document.getElementById('expbtn_'+prev).style.display='none';
    document.getElementById('tile_'+prev).classList.remove('active');
    if(expandedSource===prev) compressPanel();
    await sleep(800);
  }

  activeSource=src;
  const tile=document.getElementById('tile_'+src);
  const ph=document.getElementById('ph_'+src);
  const img=document.getElementById('img_'+src);

  tile.classList.add('active');
  setBadge(src,'',''); // spinner state
  ph.innerHTML='<div class="cam-spinner"></div>';
  ph.style.display='flex';
  img.style.display='none';

  img.onload=function(){
    ph.style.display='none'; img.style.display='block';
    setBadge(src,'live','● Live');
    setPlayBtn(src,true);
    document.getElementById('expbtn_'+src).style.display='flex';
    document.getElementById('liveDot').classList.remove('off');
    switching=false;
    // If the expanded panel is showing this source, re-connect it
    if(expandedSource===src){
      var expImg=document.getElementById('expImg');
      if(!expImg.src) expImg.src='/stream/'+src+(TOKEN?'?token='+TOKEN:'');
    }
  };
  img.onerror=function(){
    // Suppress error when src was cleared intentionally (stop button / camera switch)
    if(intentionallyOff.has(src)) return;
    ph.style.display='flex';
    ph.innerHTML='<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" opacity=".4"><path d="M23 7l-7 5 7 5V7z"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>'
      +'<span style="color:var(--red);font-size:.78rem">⚠ Stream unavailable</span>';
    img.style.display='none';
    setBadge(src,'off','Inactive');
    setPlayBtn(src,false);
    document.getElementById('expbtn_'+src).style.display='none';
    document.getElementById('liveDot').classList.add('off');
    switching=false;
  };
  intentionallyOff.delete(src); // clear flag — errors now are real failures
  img.src='/stream/'+src+(TOKEN?'?token='+TOKEN:'');
}

function openPanel(src){
  expandedSource=src;
  var panel=document.getElementById('expandedPanel');
  var expImg=document.getElementById('expImg');
  var label=src.charAt(0).toUpperCase()+src.slice(1)+' camera — expanded';
  document.getElementById('expTitle').textContent=label;
  // Open a second connection to the same stream (shareIn supports multiple subscribers)
  expImg.src='/stream/'+src+(TOKEN?'?token='+TOKEN:'');
  panel.style.display='block';
  panel.scrollIntoView({behavior:'smooth',block:'nearest'});
  // Update expand button on the tile to show it's already open
  var btn=document.getElementById('expbtn_'+src);
  btn.innerHTML=SVG_EXPAND+'<span>Expanded</span>';
  btn.style.opacity='.5';btn.style.pointerEvents='none';
}

function compressPanel(){
  var panel=document.getElementById('expandedPanel');
  var expImg=document.getElementById('expImg');
  expImg.src='';
  panel.style.display='none';
  if(expandedSource){
    var btn=document.getElementById('expbtn_'+expandedSource);
    btn.innerHTML=SVG_EXPAND+'<span>Expand</span>';
    btn.style.opacity=''; btn.style.pointerEvents='';
    expandedSource=null;
  }
}

function setBadge(src,cls,text){
  const b=document.getElementById('badge_'+src);
  b.className='cam-badge'+(cls?' '+cls:'');
  b.textContent=text;
}
function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }

// ── FILES ──
var cachedFiles=[];

function loadFiles(){
  // Show cached WS data immediately; fall back to HTTP if not yet available
  if(cachedFiles.length>0){ renderFiles(cachedFiles); return; }
  const list=document.getElementById('fileList');
  list.innerHTML='<div class="empty-state">Loading…</div>';
  fetch('/files',{headers:authHeader()})
    .then(function(r){ return r.ok?r.json():Promise.reject(r); })
    .then(function(files){ renderFiles(files); })
    .catch(function(){ list.innerHTML='<div class="empty-state">Error loading files</div>'; });
}

function renderFiles(files){
  cachedFiles=files;
  document.getElementById('fileCount').textContent=files.length+' recording'+(files.length!==1?'s':'');
  const list=document.getElementById('fileList');
  if(!files.length){
    list.innerHTML='<div class="empty-state">No recordings yet.<br>Enable archive recording in Settings.</div>';
    return;
  }
  list.innerHTML='<div class="file-list">'+files.map(function(f){
    const mb=(f.size/1048576).toFixed(1);
    const date=new Date(f.modified).toLocaleString();
    const enc=encodeURIComponent(f.name);
    return '<div class="file-row">'
      +'<span class="f-name">'+f.name+'</span>'
      +'<span class="f-meta">'+mb+' MB &nbsp;·&nbsp; '+date+'</span>'
      +'<span class="f-act">'
      +'<a class="btn btn-ghost btn-sm" href="/files/download/'+enc+(TOKEN?'?token='+TOKEN:'')+'" download="'+f.name+'">⬇ Download</a>'
      +'<button class="btn btn-danger btn-sm" onclick="deleteFile(\''+f.name+'\')">🗑</button>'
      +'</span></div>';
  }).join('')+'</div>';
}

async function deleteFile(name){
  if(!confirm('Delete '+name+'?')) return;
  const r=await fetch('/files/delete/'+encodeURIComponent(name),{method:'POST',headers:authHeader()});
  if(r.ok){ cachedFiles=[]; loadFiles(); } else alert('Delete failed');
}

// ── NAV ──
function showTab(id,btn){
  document.querySelectorAll('.page').forEach(p=>p.classList.remove('on'));
  document.querySelectorAll('.tab').forEach(b=>b.classList.remove('on'));
  document.getElementById(id).classList.add('on');
  btn.classList.add('on');
  if(id==='files') loadFiles();
}

async function logout(){
  if(activeSource){ try{document.getElementById('img_'+activeSource).src='';}catch(_){} }
  if(ws){ ws.onclose=null; ws.close(); ws=null; }
  await fetch('/logout',{method:'POST',headers:authHeader()}).catch(()=>{});
  TOKEN=''; activeSource=null; expandedSource=null; cachedFiles=[];
  document.getElementById('loginErr').textContent='';
  document.getElementById('appShell').style.display='none';
  document.getElementById('loginPage').style.display='block';
}
</script>
</body>
</html>
""".trimIndent()
