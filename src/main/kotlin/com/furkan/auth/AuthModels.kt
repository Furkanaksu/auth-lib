package com.furkan.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RegisterRequest(
    val email: String? = null,
    val password: String? = null,
    val displayName: String? = null,
    /** Opsiyonel: cihaz kimligi. Verilirse [LoginEvent] ile projeye iletilir. */
    val deviceId: String? = null,
    /** Opsiyonel: uygulamaya ozel bilgiler; kutuphane icerigini bilmez. */
    val profile: JsonObject? = null
)

@Serializable
data class LoginRequest(
    val email: String? = null,
    val password: String? = null,
    /** Opsiyonel: cihaz kimligi. Verilirse [LoginEvent] ile projeye iletilir. */
    val deviceId: String? = null,
    /** Opsiyonel: uygulamaya ozel bilgiler; kutuphane icerigini bilmez. */
    val profile: JsonObject? = null
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

/**
 * Var olan hesaba email ve sifre ekler: cihaz hesabini kaliciya cevirir.
 * Token ile cagrilir; yeni hesap acilmaz, mevcut hesabin id'si korunur.
 */
@Serializable
data class AttachRequest(
    val email: String? = null,
    val password: String? = null,
    val displayName: String? = null
)

/** Girisin nasil yapildigi. */
enum class LoginMethod {
    DEVICE,
    PASSWORD,
    SOCIAL,
    REGISTER,

    /** Var olan hesaba email/sifre eklendi; yeni hesap acilmadi. */
    ATTACH
}

/**
 * Basarili giris olayi. [AuthConfig.onLogin] ile projeye iletilir; kutuphane profilin
 * icerigini bilmez, oldugu gibi aktarir.
 *
 * @param deviceId Istekte verilmisse cihazin kimligi. Cihaz girisinde hep dolu; kayit ve
 *        parola girisinde istemci gonderdiyse dolu, yoksa null.
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
