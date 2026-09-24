package com.furkan.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

/**
 * Cihaz girisi: kullanici adi/sifre olmadan, uygulama acilisinda arka planda cagrilir.
 *
 * @param deviceId Cihazin kimligi. Hesap bununla bulunur.
 * @param deviceSecret Istemcinin uretip guvenli depoda sakladigi sir. Ilk giriste verilirse
 *        hesaba baglanir ve sonraki girislerde zorunlu olur. Verilmezse deviceId tek basina yeter
 *        (deviceId'yi ogrenen biri o hesaba girebilir).
 * @param profile Uygulamaya ozel bilgiler (platform, surum, dil...). Kutuphane icerigini bilmez;
 *        oldugu gibi [AuthConfig.onLogin] ile projeye iletir.
 */
@Serializable
data class DeviceLoginRequest(
    val deviceId: String? = null,
    val deviceSecret: String? = null,
    val profile: JsonObject? = null
)

/** Girisin nasil yapildigi. */
enum class LoginMethod { DEVICE, PASSWORD, SOCIAL, REGISTER }

/**
 * Basarili giris olayi. [AuthConfig.onLogin] ile projeye iletilir; kutuphane profilin
 * icerigini bilmez, oldugu gibi aktarir.
 *
 * @param deviceId Cihaz girisinde cihazin kimligi; diger girislerde null.
 * @param profile Istekteki ham `profile` nesnesi (platform, surum, dil...).
 */
data class LoginEvent(
    val account: AccountResponse,
    val method: LoginMethod,
    val deviceId: String? = null,
    val profile: JsonObject? = null
)

@Serializable
data class AccountResponse(
    val id: Int,
    /** Saglayici email vermediyse (Apple "email'imi gizle", Facebook izni yok) null olabilir. */
    val email: String?,
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

@Serializable
data class AuthErrorResponse(
    val status: String = "fail",
    val error: String
)
