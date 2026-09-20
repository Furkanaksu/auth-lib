@file:OptIn(ExperimentalSerializationApi::class)

package com.furkan.auth

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

// ---------- Hesap ----------

@Serializable
data class RegisterRequest(
    val email: String? = null,
    val password: String? = null,
    val displayName: String? = null
)

@Serializable
data class LoginRequest(
    val email: String? = null,
    val password: String? = null
)

@Serializable
data class RefreshRequest(
    val refreshToken: String? = null
)

@Serializable
data class AccountResponse(
    val id: Int,
    val email: String,
    val displayName: String?,
    val createdAt: String,
    val lastLoginAt: String? = null
)

/** register / login / refresh cevabi. expiresIn saniye cinsinden access token omrudur. */
@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val account: AccountResponse
)

/** GET /me cevabi: hesap + (varsa) hesaba bagli oturum. */
@Serializable
data class MeResponse(
    val account: AccountResponse,
    val session: SessionResponse? = null
)

// ---------- Oturum ----------

/**
 * Uygulama acilisinda gonderilen oturum bilgisi. Sadece deviceId zorunlu.
 * Mevcut istemcilerle uyum icin app_version / app_name adlari da kabul edilir.
 */
@Serializable
data class SessionRequest(
    val deviceId: String? = null,
    val platform: String? = null,
    @JsonNames("app_version")
    val appVersion: String? = null,
    @JsonNames("app_name")
    val appName: String? = null,
    val language: String? = null,
    val city: String? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val latitude: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val longitude: Double? = null,
    /** Uygulamaya ozel alanlar. Var olan metadata ile birlestirilir (ayni anahtar ustune yazilir). */
    val metadata: JsonObject? = null
)

@Serializable
data class SessionResponse(
    val id: Int,
    val deviceId: String,
    /** null: anonim cihaz oturumu. Dolu: oturum bu hesaba ait. */
    val accountId: Int?,
    val platform: String?,
    val appVersion: String?,
    val appName: String?,
    val language: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
    val metadata: JsonObject,
    val openCount: Int,
    val firstSeenAt: String,
    val lastSeenAt: String
)

@Serializable
data class PaginatedSessionResponse(
    val data: List<SessionResponse>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int
)

@Serializable
data class SessionFilterOptionsResponse(
    val appNames: List<String>,
    val platforms: List<String>,
    val languages: List<String>,
    val appVersions: List<String>
)

@Serializable
data class ActiveCountResponse(
    val activeUsers: Long,
    val days: Int,
    val since: String
)

// ---------- Ortak ----------

@Serializable
data class AuthErrorResponse(
    val status: String = "fail",
    val error: String
)
