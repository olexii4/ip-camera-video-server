package com.ipcamera.videoserver.auth

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuthManagerTest {

    private lateinit var auth: AuthManager

    @Before
    fun setup() {
        auth = AuthManager()
        auth.configure("test-secret-at-least-32-characters-long!")
        auth.setCredentials("admin", "secret123")
    }

    @Test
    fun `valid credentials produce non-null token`() {
        assertNotNull(auth.issueToken("admin", "secret123"))
    }

    @Test
    fun `wrong password produces null token`() {
        assertNull(auth.issueToken("admin", "wrongpass"))
    }

    @Test
    fun `unknown username produces null token`() {
        assertNull(auth.issueToken("hacker", "secret123"))
    }

    @Test
    fun `issued token validates and contains correct username`() {
        val token = auth.issueToken("admin", "secret123")!!
        val claims = auth.validateToken(token)
        assertNotNull(claims)
        assertEquals("admin", claims!!.username)
    }

    @Test
    fun `tampered token fails validation`() {
        val token = auth.issueToken("admin", "secret123")!! + "tampered"
        assertNull(auth.validateToken(token))
    }

    @Test
    fun `empty token fails validation`() {
        assertNull(auth.validateToken(""))
    }
}
