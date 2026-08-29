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
                    val bearer = extractBearer(call)
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
