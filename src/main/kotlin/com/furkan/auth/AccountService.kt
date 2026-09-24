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
    private val identities: AccountIdentityRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val tokens: TokenService,
    private val socialVerifier: SocialTokenVerifier
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
        // Hesap yoksa ya da sifresizse (sadece sosyal giris) de ayni sure hash hesaplanir:
        // email'in kayitli olup olmadigi zamanlamadan anlasilmasin.
        val valid = PasswordHasher.verify(password, record?.passwordHash ?: dummyHash)
        if (record == null || record.passwordHash == null || !valid) {
            return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_CREDENTIALS)
        }

        accounts.touchLogin(record.id)
        return AuthResult.Ok(issueTokens(accounts.findById(record.id) ?: record.response))
    }

    /**
     * Cihaz girisi: kullanici adi/sifre olmadan hesap acar ya da var olana giris yapar.
     *
     * - Cihaz daha once kaydedilmisse ayni hesap kullanilir.
     * - Kayitta sir verildiyse sonraki girislerde ayni sir istenir; yanlissa 401.
     * - Yoksa emailsiz, sifresiz yeni bir hesap acilir.
     */
    fun deviceLogin(deviceId: String?, deviceSecret: String?): AuthResult<TokenResponse> {
        val id = deviceId?.trim()
        if (id.isNullOrBlank()) {
            return AuthResult.Fail(HttpStatusCode.BadRequest, "deviceId bos olamaz")
        }
        if (id.length > 255) {
            return AuthResult.Fail(HttpStatusCode.BadRequest, "deviceId en fazla 255 karakter olabilir")
        }

        val existing = identities.findDevice(id)
        val accountId = if (existing != null) {
            // Kayitta sir varsa artik zorunludur; sabit zamanli karsilastirma.
            if (existing.secretHash != null) {
                val valid = deviceSecret != null && PasswordHasher.verify(deviceSecret, existing.secretHash)
                if (!valid) return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_DEVICE)
            }
            existing.accountId
        } else {
            val account = accounts.create(email = null, passwordHash = null, displayName = null)
            identities.linkDevice(
                accountId = account.id,
                deviceId = id,
                secretHash = deviceSecret?.takeIf { it.isNotBlank() }?.let { PasswordHasher.hash(it) }
            )
            account.id
        }

        accounts.touchLogin(accountId)
        val account = accounts.findById(accountId)
            ?: return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_DEVICE)
        return AuthResult.Ok(issueTokens(account))
    }

    /**
     * Sosyal giris. Sirayla:
     * 1. Saglayicinin token'i dogrulanir.
     * 2. (provider, providerUserId) daha once baglandiysa o hesap kullanilir.
     * 3. Degilse ve saglayici email'i DOGRULADIYSA ayni email'li hesap varsa ona baglanir.
     * 4. Hicbiri degilse yeni hesap acilir (sifresiz).
     */
    suspend fun socialLogin(
        provider: SocialProvider,
        token: String?,
        displayNameFromClient: String?
    ): AuthResult<TokenResponse> {
        if (!config.social.isEnabled(provider)) {
            return AuthResult.Fail(HttpStatusCode.NotImplemented, "$provider girisi bu sunucuda tanimli degil")
        }
        if (token.isNullOrBlank()) {
            return AuthResult.Fail(HttpStatusCode.BadRequest, "token bos olamaz")
        }

        val identity = try {
            socialVerifier.verify(provider, token)
        } catch (e: Exception) {
            null
        } ?: return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_SOCIAL_TOKEN)

        val email = identity.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() && EMAIL.matches(it) }
        val displayName = (identity.displayName ?: displayNameFromClient)?.trim()?.take(100)?.ifBlank { null }

        val linkedAccountId = identities.findAccountId(provider, identity.providerUserId)
        if (linkedAccountId != null) {
            identities.updateEmail(provider, identity.providerUserId, email)
            accounts.fillDisplayNameIfMissing(linkedAccountId, displayName)
            accounts.touchLogin(linkedAccountId)
            val account = accounts.findById(linkedAccountId)
                ?: return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_SOCIAL_TOKEN)
            return AuthResult.Ok(issueTokens(account))
        }

        // Dogrulanmamis email ile hesap birlestirmek, o email'e sahipmis gibi davranan birine
        // hesabi acmak demektir; bu yuzden sadece dogrulanmis email'de birlestirilir.
        val existing = if (email != null && identity.emailVerified && config.social.linkByVerifiedEmail) {
            accounts.findByEmail(email)
        } else {
            null
        }

        val accountId = if (existing != null) {
            existing.id
        } else {
            // Email baska bir hesapta kayitliysa bu hesaba email yazilmaz; kimlik satirinda tutulur.
            val emailForAccount = email?.takeIf { !accounts.existsByEmail(it) }
            accounts.create(emailForAccount, null, displayName).id
        }

        identities.link(accountId, identity.copy(email = email))
        accounts.fillDisplayNameIfMissing(accountId, displayName)
        accounts.touchLogin(accountId)

        val account = accounts.findById(accountId)
            ?: return AuthResult.Fail(HttpStatusCode.Unauthorized, INVALID_SOCIAL_TOKEN)
        return AuthResult.Ok(issueTokens(account))
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
        const val INVALID_SOCIAL_TOKEN = "Saglayici token'i dogrulanamadi"
        const val INVALID_DEVICE = "Cihaz dogrulanamadi"
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /** Hesap bulunamadiginda karsilastirma icin kullanilan sabit hash. */
        val dummyHash: String by lazy { PasswordHasher.hash("dummy-password-for-timing") }
    }
}
