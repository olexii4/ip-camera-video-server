package com.ipcamera.videoserver.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _sessionsFlow = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessionsFlow: StateFlow<List<SessionInfo>> = _sessionsFlow.asStateFlow()

    fun register(info: SessionInfo) {
        sessions[info.tokenId] = info
        _sessionsFlow.value = sessions.values.toList()
    }

    fun revoke(tokenId: String) {
        sessions.remove(tokenId)
        _sessionsFlow.value = sessions.values.toList()
    }

    fun clearAll() {
        sessions.clear()
        _sessionsFlow.value = emptyList()
    }

    fun activeSessions(): List<SessionInfo> = sessions.values.toList()
    fun count(): Int = sessions.size
}
