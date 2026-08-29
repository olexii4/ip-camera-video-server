package com.ipcamera.videoserver.auth

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.mindrot.jbcrypt.BCrypt
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TokenClaims(val username: String, val tokenId: String)

@Singleton
class AuthManager @Inject constructor() {

    private var secret: String = ""
    private var usernameStored: String = ""
    private var passwordHash: String = ""

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
        if (username != usernameStored) return null
        if (passwordHash.isEmpty() || !BCrypt.checkpw(password, passwordHash)) return null
        val key = Keys.hmacShaKeyFor(padSecret(secret).toByteArray())
        return Jwts.builder()
            .subject(username)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 3_600_000L))
            .signWith(key)
            .compact()
    }

    fun validateToken(token: String): TokenClaims? {
        return try {
            val key = Keys.hmacShaKeyFor(padSecret(secret).toByteArray())
            val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
            TokenClaims(username = claims.subject, tokenId = claims.id)
        } catch (_: JwtException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun padSecret(s: String): String =
        s.padEnd(32, '0').take(64)
}
