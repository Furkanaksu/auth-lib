package com.furkan.auth

import kotlinx.serialization.Serializable

/** Desteklenen sosyal giris saglayicilari. */
@Serializable
enum class SocialProvider {
    GOOGLE,
    APPLE,
    FACEBOOK;

    companion object {
        fun fromOrNull(value: String?): SocialProvider? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Saglayicidan dogrulanmis kimlik.
 *
 * @param providerUserId Saglayicinin kullanici kimligi (Google/Apple `sub`, Facebook `id`).
 *        Email degisse bile sabit kalan tek deger budur; eslesme bununla yapilir.
 * @param email Saglayicinin verdigi email. Apple "gizle" derse ya da Facebook izin vermezse null olabilir.
 * @param emailVerified Email'in saglayici tarafindan dogrulandigi bilgisi. Hesap birlestirmede sart.
 */
data class SocialIdentity(
    val provider: SocialProvider,
    val providerUserId: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?
)

/**
 * Sosyal giris istegi.
 *
 * @param token Saglayicinin istemciye verdigi token: Google/Apple icin ID token, Facebook icin access token.
 * @param displayName Apple kullanicinin adini SADECE ilk yetkilendirmede gonderir; istemci o an
 *        yakalayip buraya koyar. Diger saglayicilarda gereksizdir.
 */
@Serializable
data class SocialLoginRequest(
    val token: String? = null,
    val displayName: String? = null
)

/**
 * Sosyal giris ayarlari. Hicbiri doldurulmazsa sosyal giris ucu kapalidir.
 *
 * @param googleClientIds Kabul edilecek Google client id'leri (Android, iOS ve web icin ayri ayri).
 * @param appleAudiences Kabul edilecek Apple bundle / service id'leri.
 * @param facebookAppId Facebook uygulama kimligi.
 * @param facebookAppSecret Facebook uygulama gizli anahtari (token dogrulamasi icin).
 * @param linkByVerifiedEmail true ise saglayicinin DOGRULADIGI email mevcut bir hesapla eslesirse
 *        sosyal kimlik o hesaba baglanir. Dogrulanmamis email ile asla baglanmaz (hesap ele gecirme riski).
 * @param verifier Token dogrulayici. null ise saglayicilarin resmi uclarini kullanan varsayilan
 *        dogrulayici kurulur; testlerde sahte bir dogrulayici verilebilir.
 */
data class SocialConfig(
    val googleClientIds: Set<String> = emptySet(),
    val appleAudiences: Set<String> = emptySet(),
    val facebookAppId: String? = null,
    val facebookAppSecret: String? = null,
    val linkByVerifiedEmail: Boolean = true,
    val verifier: SocialTokenVerifier? = null
) {
    fun isEnabled(provider: SocialProvider): Boolean = when (provider) {
        SocialProvider.GOOGLE -> googleClientIds.isNotEmpty()
        SocialProvider.APPLE -> appleAudiences.isNotEmpty()
        SocialProvider.FACEBOOK -> !facebookAppId.isNullOrBlank() && !facebookAppSecret.isNullOrBlank()
    }

    val enabledProviders: Set<SocialProvider>
        get() = SocialProvider.entries.filter { isEnabled(it) }.toSet()
}
