package com.ipcamera.videoserver.auth

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mindrot.jbcrypt.BCrypt

class AuthManagerEdgeCasesTest {

    private lateinit var auth: AuthManager

    @Before
    fun setup() {
        auth = AuthManager()
    }

    @Test
    fun `issueToken returns null when secret is not yet configured`() {
        // configure() was never called — secret is empty string
        auth.setCredentials("admin", "pass")
        // The padded empty secret still produces a key, but the guard requires secret non-empty
        // Actual: issueToken returns null because secret.isEmpty() guard fires
        assertNull(auth.issueToken("admin", "pass"))
    }

    @Test
    fun `validateToken returns null when secret is not yet configured`() {
        // A token signed with one secret must not validate against an unconfigured manager
        val other = AuthManager().also {
            it.configure("some-other-secret-at-least-32-chars!!")
            it.setCredentials("admin", "pass")
        }
        val token = other.issueToken("admin", "pass")!!
        // This manager has no secret — should return null
        assertNull(auth.validateToken(token))
    }

    @Test
    fun `hashPassword produces a BCrypt hash verifiable by BCrypt checkpw`() {
        val plain = "supersecret"
        val hash = auth.hashPassword(plain)
        assertTrue("hash should start with BCrypt prefix", hash.startsWith("\$2"))
        assertTrue("BCrypt.checkpw should verify the hash", BCrypt.checkpw(plain, hash))
    }
}
