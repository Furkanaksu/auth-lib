package com.furkan.auth

import org.jetbrains.exposed.sql.Database
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Kutuphanenin kaydettigi JWT provider'in varsayilan adi. */
const val AUTH_LIB = "auth-lib"

/**
 * Kutuphanenin tum dis bagimliliklari burada toplanir. Kutuphane icinde hicbir DB baglantisi,
 * path, tablo adi ya da secret hardcode DEGILDIR; her tuketen proje kendi degerlerini verir.
 *
 * @param database        Projenin kendi Exposed [Database] objesi. Kutuphane asla connect() cagirmaz.
 * @param jwtSecret       Token imzalama anahtari. En az 32 karakter; ortam degiskeninden okunmali.
 * @param basePath        Route'larin monte edilecegi taban yol. Orn. "/auth".
 * @param tablePrefix     Tablo adlarinin onune eklenir: "prayapp_" -> prayapp_accounts, prayapp_refresh_tokens
 * @param authName        Kaydedilen JWT provider'in adi. Kendi route'larini `authenticate(authName)` ile korursun.
 * @param accessTokenTtl  Access token omru.
 * @param refreshTokenTtl Refresh token omru.
 * @param minPasswordLength Kayitta istenen en kisa sifre.
 * @param social          Sosyal giris ayarlari. Bos birakilirsa sosyal giris ucu kapalidir.
 * @param onLogin         Giris sonrasi calisan geri cagri ([LoginEvent]).
 */
data class AuthConfig(
    val database: Database,
    val jwtSecret: String,
    val basePath: String = "/auth",
    val tablePrefix: String = "",
    val authName: String = AUTH_LIB,
    val jwtIssuer: String = "auth-lib",
    val jwtAudience: String = "auth-lib-clients",
    val accessTokenTtl: Duration = 1.hours,
    val refreshTokenTtl: Duration = 30.days,
    val minPasswordLength: Int = 8,
    val social: SocialConfig = SocialConfig(),
    /**
     * Her basarili giristen sonra cagrilir. Kutuphane profil alanlarini bilmez; proje bunu
     * kendi kullanici tablosuna yazmak icin kullanir (orn. user-me-lib).
     * Varsayilan: hicbir sey yapma.
     */
    val onLogin: (LoginEvent) -> Unit = { }
) {
    val accounts: AccountTable by lazy { AccountTable("${tablePrefix}accounts") }
    val identities: AccountIdentityTable by lazy {
        AccountIdentityTable("${tablePrefix}account_identities", accounts)
    }
    val refreshTokens: RefreshTokenTable by lazy { RefreshTokenTable("${tablePrefix}refresh_tokens", accounts) }

    init {
        require(jwtSecret.length >= 32) { "jwtSecret en az 32 karakter olmali" }
        require(basePath.startsWith("/")) { "basePath '/' ile baslamali: $basePath" }
        require(authName.isNotBlank()) { "authName bos olamaz" }
        require(accessTokenTtl.isPositive() && refreshTokenTtl.isPositive()) { "token omurleri pozitif olmali" }
        require(minPasswordLength >= 6) { "minPasswordLength en az 6 olmali" }
    }

    /** data class toString'i secret'i loglara sizdirmasin. */
    override fun toString(): String =
        "AuthConfig(basePath=$basePath, tablePrefix=$tablePrefix, authName=$authName, " +
            "socialProviders=${social.enabledProviders})"
}
