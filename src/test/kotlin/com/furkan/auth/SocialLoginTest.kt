package com.furkan.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Saglayiciya gercekten gidilmez: [SocialConfig.verifier] ile sahte dogrulayici verilir.
 * Token degeri burada "hangi kimligi donsun" anahtaridir.
 */
class SocialLoginTest {

    private val kimlikler = mutableMapOf<String, SocialIdentity>()

    private fun config(dbName: String, linkByVerifiedEmail: Boolean = true) = AuthConfig(
        database = org.jetbrains.exposed.sql.Database.connect(
            "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        ),
        jwtSecret = TEST_SECRET,
        tablePrefix = "${dbName}_",
        social = SocialConfig(
            googleClientIds = setOf("google-client-id"),
            appleAudiences = setOf("com.furkan.app"),
            facebookAppId = "fb-app",
            facebookAppSecret = "fb-secret",
            linkByVerifiedEmail = linkByVerifiedEmail,
            verifier = { _, token -> kimlikler[token] }
        )
    ).also { it.migrate() }

    private fun kimlik(
        token: String,
        provider: SocialProvider,
        providerUserId: String,
        email: String? = null,
        emailVerified: Boolean = true,
        displayName: String? = null
    ) {
        kimlikler[token] = SocialIdentity(provider, providerUserId, email, emailVerified, displayName)
    }

    @Test
    fun `ilk google girisi hesap acar, ikinci giris ayni hesaba dusr`() = testApplication {
        setup(config("g1"))
        kimlik("tok", SocialProvider.GOOGLE, "google-123", "ali@example.com", displayName = "Ali")

        val first = postJson("/auth/social/google", """{"token":"tok"}""")
        assertEquals(HttpStatusCode.OK, first.status)
        val a = first.decode<TokenResponse>()
        assertEquals("ali@example.com", a.account.email)
        assertEquals("Ali", a.account.displayName)

        val second = postJson("/auth/social/GOOGLE", """{"token":"tok"}""").decode<TokenResponse>()
        assertEquals(a.account.id, second.account.id, "ayni saglayici kimligi ayni hesap")
        // Access token ayni saniyede ayni claim'lerle uretilirse birebir ayni olur; refresh rastgeledir.
        assertNotEquals(a.refreshToken, second.refreshToken)
    }

    @Test
    fun `saglayici email degisse bile ayni hesap bulunur`() = testApplication {
        val cfg = config("g2")
        setup(cfg)
        kimlik("tok1", SocialProvider.GOOGLE, "google-123", "eski@example.com")
        val first = postJson("/auth/social/google", """{"token":"tok1"}""").decode<TokenResponse>()

        kimlik("tok2", SocialProvider.GOOGLE, "google-123", "yeni@example.com")
        val second = postJson("/auth/social/google", """{"token":"tok2"}""").decode<TokenResponse>()

        assertEquals(first.account.id, second.account.id, "eslesme providerUserId ile yapilir")
        assertEquals(1, accountCount(cfg))
    }

    @Test
    fun `dogrulanmis email mevcut sifreli hesaba baglanir`() = testApplication {
        val cfg = config("g3")
        setup(cfg)
        val sifreli = register("veli@example.com")

        kimlik("tok", SocialProvider.GOOGLE, "google-9", "veli@example.com", emailVerified = true)
        val social = postJson("/auth/social/google", """{"token":"tok"}""").decode<TokenResponse>()

        assertEquals(sifreli.account.id, social.account.id, "ayni hesaba baglandi")
        assertEquals(1, accountCount(cfg))
        assertEquals(1, identityCount(cfg))
    }

    @Test
    fun `dogrulanmamis email mevcut hesaba baglanmaz`() = testApplication {
        val cfg = config("g4")
        setup(cfg)
        val sifreli = register("ayse@example.com")

        kimlik("tok", SocialProvider.GOOGLE, "google-7", "ayse@example.com", emailVerified = false)
        val social = postJson("/auth/social/google", """{"token":"tok"}""").decode<TokenResponse>()

        assertNotEquals(sifreli.account.id, social.account.id, "hesap ele gecirilemez")
        assertNull(social.account.email, "email baska hesapta oldugu icin bu hesaba yazilmaz")
        assertEquals(2, accountCount(cfg))
    }

    @Test
    fun `linkByVerifiedEmail kapaliysa birlestirme yapilmaz`() = testApplication {
        val cfg = config("g5", linkByVerifiedEmail = false)
        setup(cfg)
        val sifreli = register("mehmet@example.com")

        kimlik("tok", SocialProvider.GOOGLE, "google-5", "mehmet@example.com", emailVerified = true)
        val social = postJson("/auth/social/google", """{"token":"tok"}""").decode<TokenResponse>()

        assertNotEquals(sifreli.account.id, social.account.id)
        assertEquals(2, accountCount(cfg))
    }

    @Test
    fun `apple emailsiz gelebilir, ad istekten alinir`() = testApplication {
        val cfg = config("g6")
        setup(cfg)
        kimlik("apple-tok", SocialProvider.APPLE, "apple-abc", email = null)

        val response = postJson("/auth/social/apple", """{"token":"apple-tok","displayName":"Zeynep"}""")
        assertEquals(HttpStatusCode.OK, response.status)
        val account = response.decode<TokenResponse>().account
        assertNull(account.email)
        assertEquals("Zeynep", account.displayName, "Apple adi sadece ilk yetkilendirmede gonderir")

        // Ikinci giriste ad tekrar gelmese de kayitli kalir.
        val again = postJson("/auth/social/apple", """{"token":"apple-tok"}""").decode<TokenResponse>()
        assertEquals(account.id, again.account.id)
        assertEquals("Zeynep", again.account.displayName)
        assertEquals(1, accountCount(cfg))
    }

    @Test
    fun `ayni kisi google ve apple ile tek hesapta birlesir`() = testApplication {
        val cfg = config("g7")
        setup(cfg)
        kimlik("g", SocialProvider.GOOGLE, "google-1", "ortak@example.com")
        kimlik("a", SocialProvider.APPLE, "apple-1", "ortak@example.com")

        val google = postJson("/auth/social/google", """{"token":"g"}""").decode<TokenResponse>()
        val apple = postJson("/auth/social/apple", """{"token":"a"}""").decode<TokenResponse>()

        assertEquals(google.account.id, apple.account.id)
        assertEquals(1, accountCount(cfg))
        assertEquals(2, identityCount(cfg), "iki kimlik, tek hesap")
    }

    @Test
    fun `sosyal hesaba sifreyle girilemez`() = testApplication {
        setup(config("g8"))
        kimlik("tok", SocialProvider.FACEBOOK, "fb-1", "sosyal@example.com")
        postJson("/auth/social/facebook", """{"token":"tok"}""")

        val response = postJson("/auth/login", """{"email":"sosyal@example.com","password":"herhangi-bir-sifre"}""")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `gecersiz token, eksik token ve bilinmeyen saglayici reddedilir`() = testApplication {
        setup(config("g9"))

        assertEquals(
            HttpStatusCode.Unauthorized,
            postJson("/auth/social/google", """{"token":"bilinmeyen"}""").status
        )
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/social/google", """{}""").status)
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/social/twitter", """{"token":"x"}""").status)
    }

    @Test
    fun `tanimsiz saglayici 501 doner`() = testApplication {
        val cfg = AuthConfig(
            database = org.jetbrains.exposed.sql.Database.connect(
                "jdbc:h2:mem:g10;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            ),
            jwtSecret = TEST_SECRET,
            tablePrefix = "g10_",
            social = SocialConfig(googleClientIds = setOf("sadece-google"), verifier = { _, _ -> null })
        ).also { it.migrate() }
        setup(cfg)

        assertEquals(HttpStatusCode.NotImplemented, postJson("/auth/social/apple", """{"token":"x"}""").status)
        assertEquals(setOf(SocialProvider.GOOGLE), cfg.social.enabledProviders)
    }

    private fun accountCount(config: AuthConfig): Long =
        transaction(config.database) { config.accounts.selectAll().count() }

    private fun identityCount(config: AuthConfig): Long =
        transaction(config.database) { config.identities.selectAll().count() }
}
