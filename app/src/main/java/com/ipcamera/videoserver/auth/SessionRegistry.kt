package com.ipcamera.videoserver.auth

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SessionInfo(
    val tokenId: String,
    val username: String,
    val remoteAddress: String,
    val connectedAt: Long = System.currentTimeMillis(),
)

@Singleton
class SessionRegistry @Inject constructor() {
    private val sessions = ConcurrentHashMap<String, SessionInfo>()

    fun register(info: SessionInfo) { sessions[info.tokenId] = info }
    fun revoke(tokenId: String) { sessions.remove(tokenId) }
    fun activeSessions(): List<SessionInfo> = sessions.values.toList()
    fun count(): Int = sessions.size
}
