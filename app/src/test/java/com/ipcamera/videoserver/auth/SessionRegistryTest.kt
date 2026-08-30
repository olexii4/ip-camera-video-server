package com.ipcamera.videoserver.auth

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionRegistryTest {

    private lateinit var registry: SessionRegistry

    private fun info(id: String, username: String = "admin") =
        SessionInfo(tokenId = id, username = username, remoteAddress = "127.0.0.1")

    @Before
    fun setup() {
        registry = SessionRegistry()
    }

    @Test
    fun `register increments count and is visible in activeSessions`() {
        registry.register(info("tok1"))
        assertEquals(1, registry.count())
        assertEquals("tok1", registry.activeSessions().first().tokenId)
    }

    @Test
    fun `revoke removes the matching session by tokenId`() {
        registry.register(info("tok1"))
        registry.register(info("tok2"))
        registry.revoke("tok1")

        assertEquals(1, registry.count())
        assertNull(registry.activeSessions().find { it.tokenId == "tok1" })
        assertNotNull(registry.activeSessions().find { it.tokenId == "tok2" })
    }

    @Test
    fun `clearAll empties all sessions`() {
        registry.register(info("tok1"))
        registry.register(info("tok2"))
        registry.register(info("tok3"))
        registry.clearAll()

        assertEquals(0, registry.count())
        assertTrue(registry.activeSessions().isEmpty())
    }

    @Test
    fun `registering the same tokenId replaces the existing entry`() {
        registry.register(info("tok1", username = "alice"))
        registry.register(info("tok1", username = "bob"))

        assertEquals(1, registry.count())
        assertEquals("bob", registry.activeSessions().first().username)
    }
}
