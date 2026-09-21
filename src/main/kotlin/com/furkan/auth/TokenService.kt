package com.furkan.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date

/**
 * Access token: imzali JWT (durumsuz dogrulanir).
 * Refresh token: rastgele opak deger; DB'de sadece SHA-256 ozeti tutulur, iptal edilebilir.
 */
internal class TokenService(private val config: AuthConfig) {

    private val algorithm = Algorithm.HMAC256(config.jwtSecret)
    private val random = SecureRandom()

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.jwtIssuer)
        .withAudience(config.jwtAudience)
        .withClaim(CLAIM_TYPE, TYPE_ACCESS)
        .build()

    fun createAccessToken(accountId: Int, email: String?): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(accountId.toString())
            // Emailsiz hesaplarda (sosyal giris) claim hic yazilmaz.
            .apply { if (email != null) withClaim(CLAIM_EMAIL, email) }
            .withClaim(CLAIM_TYPE, TYPE_ACCESS)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + config.accessTokenTtl.inWholeMilliseconds))
            .sign(algorithm)
    }

    fun newRefreshToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hashRefreshToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val CLAIM_EMAIL = "email"
        const val CLAIM_TYPE = "typ"
        const val TYPE_ACCESS = "access"
    }
}
