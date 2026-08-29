package com.ipcamera.videoserver.auth

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TokenClaims(val username: String, val tokenId: String)

@Singleton
class AuthManager @Inject constructor() {

    @Volatile private var secret: String = ""
    @Volatile private var usernameStored: String = ""
    @Volatile private var passwordHash: String = ""
    @Volatile var authRequired: Boolean = true

    fun configure(secret: String) {
        this.secret = secret
    }

    fun setCredentials(username: String, plainPassword: String) {
        usernameStored = username
        passwordHash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(10))
    }

    fun setHashedCredentials(username: String, hash: String) {
        usernameStored = username
        passwordHash = hash
    }

    fun hashPassword(plain: String): String = BCrypt.hashpw(plain, BCrypt.gensalt(10))

    fun issueToken(username: String, password: String): String? {
        if (secret.isEmpty() || passwordHash.isEmpty()) return null
        if (username != usernameStored) return null
        if (!BCrypt.checkpw(password, passwordHash)) return null
        val key = Keys.hmacShaKeyFor(deriveKey(secret))
        return Jwts.builder()
            .subject(username)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 3_600_000L))
            .signWith(key)
            .compact()
    }

    fun validateToken(token: String): TokenClaims? {
        if (secret.isEmpty()) return null
        return try {
            val key = Keys.hmacShaKeyFor(deriveKey(secret))
            val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
            TokenClaims(username = claims.subject, tokenId = claims.id)
        } catch (_: JwtException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun deriveKey(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
}
