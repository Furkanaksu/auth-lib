package com.furkan.auth

import io.ktor.http.HttpStatusCode
import java.time.LocalDateTime

/** Servis sonucu: basari ya da HTTP durumu + mesajla hata. */
internal sealed interface AuthResult<out T> {
    data class Ok<T>(val value: T) : AuthResult<T>
    data class Fail(val status: HttpStatusCode, val message: String) : AuthResult<Nothing>
}

internal class AccountService(
    private val config: AuthConfig,
    private val accounts: AccountRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val tokens: TokenService
) {

    fun register(email: String?, password: String?, displayName: String?): AuthResult<TokenResponse> {
        val normalized = email?.trim()?.lowercase()
        if (normalized.isNullOrBlank() || normalized.length > 255 || !EMAIL.matches(normalized)) {
            return AuthResult.Fail(HttpStatusCode.BadRequest, "Gecerli bir email girin")
        }
        if (password.isNullOrEmpty() || password.length < config.minPasswordLength) {
            return AuthResult.Fail(
                HttpStatusCode.BadRequest,
                "Sifre en az ${config.minPasswordLength} karakter olmali"
            )
        }
        if (password.length > MAX_PASSWORD) {
            return AuthResult.Fail(HttpStatusCode.BadRequest, "Sifre en fazla $MAX_PASSWORD karakter olabilir")
        }
        if (accounts.existsByEmail(normalized)) {
            return AuthResult.Fail(HttpStatusCode.Conflict, "Bu email ile kayitli bir hesap var")
        }

        val account = try {
            accounts.create(normalized, PasswordHasher.hash(password), displayName?.trim()?.take(100)?.ifBlank { null })
        } catch (e: Exception) {
            // Ayni anda iki kayit: unique index ikincisini reddeder.
            return AuthResult.Fail(HttpStatusCode.Conflict, "Bu email ile kayitli bir hesap var")
        }
        return AuthResult.Ok(issueTokens(account))
    }

    fun login(email: String?, password: String?): AuthResult<TokenResponse> {
        val normalized = email?.trim()?.lowercase()
        if (normalized.isNullOrBlank() || password.isNullOrEmpty()) {
            return AuthResult.Fail(HttpStatusCode.BadRequest, "Email ve sifre bos olamaz")
        }

        val record = accounts.findByEmail(normalized)
        // Hesap yoksa da ayni sure hash hesaplanir: email'in kayitli olup olmadigi zamanlamadan anlasilmasin.
        val valid = PasswordHasher.verify(password, record?.passwordHash ?: dummyHash)
        if (record == null || !valid) {
            return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_CREDENTIALS)
        }

        accounts.touchLogin(record.id)
        return AuthResult.Ok(issueTokens(accounts.findById(record.id) ?: record.response))
    }

    /**
     * Refresh token'i dondurur (rotation): eskisi iptal edilir, yeni cift verilir.
     * Iptal edilmis bir token tekrar gelirse token calinmis kabul edilir ve hesabin tum token'lari iptal edilir.
     */
    fun refresh(refreshToken: String?): AuthResult<TokenResponse> {
        if (refreshToken.isNullOrBlank()) {
            return AuthResult.Fail(HttpStatusCode.BadRequest, "refreshToken bos olamaz")
        }
        val hash = tokens.hashRefreshToken(refreshToken)
        val record = refreshTokens.find(hash)
            ?: return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_REFRESH)

        if (record.revokedAt != null) {
            refreshTokens.revokeAll(record.accountId)
            return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_REFRESH)
        }
        if (record.expiresAt.isBefore(LocalDateTime.now())) {
            return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_REFRESH)
        }
        // Iki paralel istekten sadece biri kazanir.
        if (!refreshTokens.revoke(hash)) {
            return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_REFRESH)
        }

        val account = accounts.findById(record.accountId)
            ?: return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_REFRESH)
        return AuthResult.Ok(issueTokens(account))
    }

    /** Cikis: verilen refresh token'i iptal eder. Bilinmeyen token da sessizce kabul edilir. */
    fun logout(refreshToken: String?): AuthResult<Unit> {
        if (refreshToken.isNullOrBlank()) {
            return AuthResult.Fail(HttpStatusCode.BadRequest, "refreshToken bos olamaz")
        }
        refreshTokens.revoke(tokens.hashRefreshToken(refreshToken))
        return AuthResult.Ok(Unit)
    }

    fun findAccount(id: Int): AccountResponse? = accounts.findById(id)

    private fun issueTokens(account: AccountResponse): TokenResponse {
        val refresh = tokens.newRefreshToken()
        val expiresAt = LocalDateTime.now().plusSeconds(config.refreshTokenTtl.inWholeSeconds)
        refreshTokens.create(account.id, tokens.hashRefreshToken(refresh), expiresAt)

        return TokenResponse(
            accessToken = tokens.createAccessToken(account.id, account.email),
            refreshToken = refresh,
            expiresIn = config.accessTokenTtl.inWholeSeconds,
            account = account
        )
    }

    private companion object {
        const val MAX_PASSWORD = 128
        const val INVALID_CREDENTIALS = "Gecersiz email veya sifre"
        const val INVALID_REFRESH = "Gecersiz ya da suresi dolmus refresh token"
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /** Hesap bulunamadiginda karsilastirma icin kullanilan sabit hash. */
        val dummyHash: String by lazy { PasswordHasher.hash("dummy-password-for-timing") }
    }
}
