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
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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
            routing {
                get("/ping") { call.respondText("pong") }

                get("/") { call.respondText(WEB_UI_HTML, ContentType.Text.Html) }

                post("/oauth/token") {
                    val params = call.receiveParameters()
                    val username = params["username"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing username")
                    val password = params["password"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing password")
                    val token = authManager.issueToken(username, password)
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, """{"error":"invalid_credentials"}""")
                    val claims = authManager.validateToken(token)!!
                    sessionRegistry.register(SessionInfo(claims.tokenId, claims.username, call.request.local.remoteAddress))
                    call.respondText(json.encodeToString(TokenResponse(token, "Bearer", 3600)), ContentType.Application.Json)
                }

                // Returns which camera sources are physically present on this device
                get("/cameras") {
                    requireAuth(call) ?: return@get
                    val sources = cameraStreamManager.availableSources().map { it.id }
                    call.respondText(json.encodeToString(sources), ContentType.Application.Json)
                }

                get("/stream/{source}") {
                    requireAuth(call) ?: return@get
                    val sourceId = call.parameters["source"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val source = CameraSource.entries.firstOrNull { it.id == sourceId }
                        ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown source: $sourceId")
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.respondBytesWriter(contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$BOUNDARY")) {
                        cameraStreamManager.getStream(source).collect { jpegBytes ->
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

private val WEB_UI_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>IP Camera Server</title>
<style>
*{box-sizing:border-box}
body{font-family:system-ui,sans-serif;background:#0d1117;color:#e6edf3;margin:0;padding:0}
header{background:#161b22;border-bottom:1px solid #30363d;padding:14px 20px;display:flex;align-items:center;gap:12px}
header h1{margin:0;font-size:1.1rem}
nav{display:flex;gap:4px;margin-left:auto}
nav button{background:none;border:1px solid #30363d;color:#8b949e;border-radius:6px;padding:6px 14px;cursor:pointer;font-size:.85rem}
nav button.active{background:#238636;color:#fff;border-color:#238636}
.page{display:none;padding:20px}
.page.active{display:block}
/* Login */
#loginPage{max-width:360px;margin:60px auto}
.card{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:24px}
label{display:block;font-size:.85rem;color:#8b949e;margin-bottom:4px}
input[type=text],input[type=password]{width:100%;padding:9px 12px;background:#0d1117;border:1px solid #30363d;color:#e6edf3;border-radius:6px;font-size:.95rem;margin-bottom:14px}
.btn{display:inline-block;padding:9px 18px;background:#238636;color:#fff;border:none;border-radius:6px;cursor:pointer;font-size:.9rem}
.btn:hover{background:#2ea043}
.btn.danger{background:#b91c1c}.btn.danger:hover{background:#991b1b}
.btn.secondary{background:#21262d;border:1px solid #30363d}.btn.secondary:hover{background:#30363d}
#loginErr{color:#f85149;font-size:.85rem;margin-top:8px}
/* Cameras */
#camGrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}
.cam-box h3{margin:0 0 8px;font-size:.8rem;color:#8b949e;text-transform:uppercase;letter-spacing:.06em}
.cam-box img{width:100%;border-radius:8px;background:#161b22;display:block;min-height:160px}
.cam-box .err{color:#f85149;font-size:.8rem;margin-top:6px;display:none}
/* Files */
#fileList{border:1px solid #30363d;border-radius:8px;overflow:hidden}
.file-row{display:flex;align-items:center;padding:10px 14px;border-bottom:1px solid #21262d;gap:8px}
.file-row:last-child{border-bottom:none}
.file-row:hover{background:#161b22}
.fname{flex:1;font-size:.9rem;word-break:break-all}
.fsize{font-size:.8rem;color:#8b949e;white-space:nowrap;margin-right:8px}
.fdate{font-size:.75rem;color:#6e7681;white-space:nowrap;margin-right:8px}
#fileTools{display:flex;gap:8px;margin-bottom:14px}
#emptyMsg{padding:24px;text-align:center;color:#6e7681;font-size:.9rem}
</style>
</head>
<body>
<!-- LOGIN -->
<div id="loginPage" class="page active">
  <div class="card">
    <h2 style="margin-top:0">📷 IP Camera Server</h2>
    <label>Username</label>
    <input id="user" type="text" value="admin" autocomplete="username">
    <label>Password</label>
    <input id="pass" type="password" value="admin" autocomplete="current-password">
    <button class="btn" onclick="login()">Connect</button>
    <div id="loginErr"></div>
  </div>
</div>

<!-- MAIN APP -->
<div id="app" style="display:none">
  <header>
    <h1>📷 IP Camera</h1>
    <nav>
      <button class="active" onclick="showTab('cameras',this)">Cameras</button>
      <button onclick="showTab('files',this)">Files</button>
    </nav>
  </header>

  <!-- Cameras tab -->
  <div id="cameras" class="page active">
    <div id="camGrid"></div>
  </div>

  <!-- Files tab -->
  <div id="files" class="page">
    <div id="fileTools">
      <button class="btn secondary" onclick="loadFiles()">↻ Refresh</button>
    </div>
    <div id="fileList"><div id="emptyMsg">Loading…</div></div>
  </div>
</div>

<script>
let TOKEN = '';

async function login() {
  const err = document.getElementById('loginErr');
  err.textContent = '';
  const body = new URLSearchParams({username: document.getElementById('user').value, password: document.getElementById('pass').value});
  const r = await fetch('/oauth/token', {method:'POST', body});
  if (!r.ok) { err.textContent = 'Invalid credentials'; return; }
  TOKEN = (await r.json()).access_token;
  document.getElementById('loginPage').style.display = 'none';
  document.getElementById('app').style.display = 'block';
  await loadCameras();
}

async function loadCameras() {
  const r = await fetch('/cameras', {headers:{Authorization:'Bearer '+TOKEN}});
  const sources = r.ok ? await r.json() : ['main','front'];
  const grid = document.getElementById('camGrid');
  grid.innerHTML = '';
  sources.forEach(src => {
    const box = document.createElement('div');
    box.className = 'cam-box';
    const url = '/stream/' + src + '?token=' + TOKEN;
    box.innerHTML = '<h3>' + src + ' camera</h3><img src="' + url + '" alt="' + src + '" onerror="this.nextElementSibling.style.display=\'block\'"><div class="err">⚠ Stream unavailable</div>';
    grid.appendChild(box);
  });
}

async function loadFiles() {
  const list = document.getElementById('fileList');
  list.innerHTML = '<div id="emptyMsg">Loading…</div>';
  const r = await fetch('/files', {headers:{Authorization:'Bearer '+TOKEN}});
  if (!r.ok) { list.innerHTML = '<div id="emptyMsg">Error loading files</div>'; return; }
  const files = await r.json();
  if (!files.length) { list.innerHTML = '<div id="emptyMsg">No recordings yet</div>'; return; }
  list.innerHTML = files.map(f => {
    const mb = (f.size/1048576).toFixed(1);
    const date = new Date(f.modified).toLocaleString();
    return '<div class="file-row">'
      + '<span class="fname">' + f.name + '</span>'
      + '<span class="fsize">' + mb + ' MB</span>'
      + '<span class="fdate">' + date + '</span>'
      + '<a class="btn" href="/files/download/' + f.name + '?token=' + TOKEN + '" download="' + f.name + '">⬇</a>'
      + '<button class="btn danger" onclick="deleteFile(\'' + f.name + '\')">🗑</button>'
      + '</div>';
  }).join('');
}

async function deleteFile(name) {
  if (!confirm('Delete ' + name + '?')) return;
  const r = await fetch('/files/delete/' + name, {method:'POST', headers:{Authorization:'Bearer '+TOKEN}});
  if (r.ok) loadFiles();
  else alert('Delete failed');
}

function showTab(id, btn) {
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('nav button').forEach(b => b.classList.remove('active'));
  document.getElementById(id).classList.add('active');
  btn.classList.add('active');
  if (id === 'files') loadFiles();
}

document.addEventListener('keydown', e => { if(e.key==='Enter' && document.getElementById('loginPage').style.display!=='none') login(); });
</script>
</body>
</html>
""".trimIndent()
