package com.ipcamera.videoserver.server

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
import javax.inject.Inject
import javax.inject.Singleton

private const val BOUNDARY = "frame"

@Singleton
class WebServer @Inject constructor(
    private val authManager: AuthManager,
    private val sessionRegistry: SessionRegistry,
    private val cameraStreamManager: CameraStreamManager,
) {
    private var server: ApplicationEngine? = null

    fun start(port: Int) {
        server = embeddedServer(CIO, port = port) {
            routing {
                get("/ping") { call.respondText("pong") }

                // Browser-friendly login page
                get("/") {
                    call.respondText(WEB_UI_HTML, ContentType.Text.Html)
                }

                post("/oauth/token") {
                    val params = call.receiveParameters()
                    val username = params["username"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing username")
                    val password = params["password"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing password")

                    val token = authManager.issueToken(username, password)
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            """{"error":"invalid_credentials"}""",
                        )

                    val claims = authManager.validateToken(token)!!
                    sessionRegistry.register(
                        SessionInfo(
                            tokenId = claims.tokenId,
                            username = claims.username,
                            remoteAddress = call.request.local.remoteAddress,
                        ),
                    )
                    call.respondText(
                        Json.encodeToString(TokenResponse(token, "Bearer", 3600)),
                        ContentType.Application.Json,
                    )
                }

                get("/stream/{source}") {
                    // Accept token from Authorization header OR ?token= query param
                    val bearer = extractBearer(call)
                        ?: call.request.queryParameters["token"]
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    authManager.validateToken(bearer)
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)

                    val sourceId = call.parameters["source"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val source = CameraSource.entries.firstOrNull { it.id == sourceId }
                        ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown source: $sourceId")

                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.respondBytesWriter(
                        contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$BOUNDARY"),
                    ) {
                        cameraStreamManager.getStream(source).collect { jpegBytes ->
                            val header = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n"
                            writeStringUtf8(header)
                            writeFully(jpegBytes)
                            writeStringUtf8("\r\n")
                            flush()
                        }
                    }
                }

                get("/status") {
                    val bearer = extractBearer(call)
                        ?: call.request.queryParameters["token"]
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    authManager.validateToken(bearer)
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)

                    val sessions = sessionRegistry.activeSessions().map {
                        SessionDto(it.username, it.remoteAddress, it.connectedAt)
                    }
                    call.respondText(
                        Json.encodeToString(StatusResponse(running = true, activeSessions = sessions)),
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 2_000)
        server = null
    }

    private fun extractBearer(call: ApplicationCall): String? =
        call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.removePrefix("Bearer ")
            ?.trim()
}

@Serializable
private data class TokenResponse(val access_token: String, val token_type: String, val expires_in: Int)

@Serializable
private data class StatusResponse(val running: Boolean, val activeSessions: List<SessionDto>)

@Serializable
private data class SessionDto(val username: String, val remoteAddress: String, val connectedAt: Long)

private val WEB_UI_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>IP Camera Server</title>
<style>
  body{font-family:sans-serif;background:#0d1117;color:#e6edf3;margin:0;padding:24px}
  h1{font-size:1.4rem;margin-bottom:20px}
  input{width:100%;box-sizing:border-box;padding:10px;margin:6px 0 14px;background:#161b22;border:1px solid #30363d;color:#e6edf3;border-radius:6px;font-size:1rem}
  button{width:100%;padding:10px;background:#238636;color:#fff;border:none;border-radius:6px;font-size:1rem;cursor:pointer}
  button:hover{background:#2ea043}
  .streams{display:none;margin-top:24px}
  .stream-box{margin-bottom:24px}
  .stream-box h3{margin:0 0 8px;font-size:0.9rem;color:#8b949e;text-transform:uppercase}
  img.stream{width:100%;border-radius:8px;background:#161b22;min-height:120px}
  #msg{margin-top:12px;color:#f85149;font-size:0.9rem}
  a.copy{font-size:0.75rem;color:#58a6ff;text-decoration:none;margin-left:8px}
</style>
</head>
<body>
<h1>📷 IP Camera Server</h1>
<form id="loginForm">
  <label>Username</label>
  <input id="user" type="text" value="admin" autocomplete="username">
  <label>Password</label>
  <input id="pass" type="password" value="admin" autocomplete="current-password">
  <button type="submit">Connect</button>
  <div id="msg"></div>
</form>
<div class="streams" id="streams">
  <div class="stream-box">
    <h3>Main camera <a class="copy" id="mainLink" href="#">copy URL</a></h3>
    <img class="stream" id="imgMain" alt="main camera">
  </div>
  <div class="stream-box">
    <h3>Front camera <a class="copy" id="frontLink" href="#">copy URL</a></h3>
    <img class="stream" id="imgFront" alt="front camera">
  </div>
</div>
<script>
document.getElementById('loginForm').onsubmit = async e => {
  e.preventDefault();
  const msg = document.getElementById('msg');
  msg.textContent = '';
  const body = new URLSearchParams({username: document.getElementById('user').value, password: document.getElementById('pass').value});
  const r = await fetch('/oauth/token', {method:'POST', body});
  if (!r.ok) { msg.textContent = 'Login failed'; return; }
  const {access_token} = await r.json();
  const base = window.location.origin;
  const mainUrl = base + '/stream/main?token=' + access_token;
  const frontUrl = base + '/stream/front?token=' + access_token;
  document.getElementById('imgMain').src = mainUrl;
  document.getElementById('imgFront').src = frontUrl;
  document.getElementById('mainLink').onclick = e => { e.preventDefault(); navigator.clipboard.writeText(mainUrl); };
  document.getElementById('frontLink').onclick = e => { e.preventDefault(); navigator.clipboard.writeText(frontUrl); };
  document.getElementById('loginForm').style.display = 'none';
  document.getElementById('streams').style.display = 'block';
};
</script>
</body>
</html>
""".trimIndent()
