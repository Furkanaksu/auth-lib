package com.furkan.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Cihaz hesabini kaliciya cevirme.
 *
 * Kritik nokta: yeni hesap ACILMAZ. Kullanicinin kayit olmadan once biriktirdigi her sey
 * (oturum, gecmis, premium) hesabin id'sine bagli oldugu icin yerinde kalir.
 */
class AttachTest {

    private val olaylar = mutableListOf<LoginEvent>()

    private fun config(dbName: String) = AuthConfig(
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"),
        jwtSecret = TEST_SECRET,
        tablePrefix = "${dbName}_",
        onLogin = { olaylar += it }
    ).also { it.migrate() }

    private fun hesapSayisi(config: AuthConfig): Long =
        transaction(config.database) { config.accounts.selectAll().count() }

    private suspend fun ApplicationTestBuilderAlias.cihazGirisi(deviceId: String = "cihaz-1"): TokenResponse =
        postJson("/auth/device", """{"deviceId":"$deviceId"}""").decode()

    @Test
    fun `cihaz hesabi ayni id ile kalici hesaba donusur`() = testApplication {
        val cfg = config("at1")
        setup(cfg)

        val cihaz = cihazGirisi()
        assertNull(cihaz.account.email, "cihaz hesabinin emaili yoktur")
        assertEquals(1, hesapSayisi(cfg))

        val sonuc = postJson(
            "/auth/attach",
            """{"email":"ali@ornek.com","password":"sifre12345","displayName":"Ali"}""",
            cihaz.accessToken
        )

        assertEquals(HttpStatusCode.OK, sonuc.status)
        val kalici = sonuc.decode<TokenResponse>()
        assertEquals(cihaz.account.id, kalici.account.id, "AYNI hesap: gecmis korunur")
        assertEquals("ali@ornek.com", kalici.account.email)
        assertEquals("Ali", kalici.account.displayName)
        assertEquals(1, hesapSayisi(cfg), "yeni hesap acilmadi")
    }

    @Test
    fun `donusumden sonra email sifre ile girilebilir`() = testApplication {
        val cfg = config("at2")
        setup(cfg)
        val cihaz = cihazGirisi()
        postJson("/auth/attach", """{"email":"ali@ornek.com","password":"sifre12345"}""", cihaz.accessToken)

        val giris = postJson("/auth/login", """{"email":"ali@ornek.com","password":"sifre12345"}""")
        assertEquals(HttpStatusCode.OK, giris.status)
        assertEquals(cihaz.account.id, giris.decode<TokenResponse>().account.id, "ayni hesaba giriliyor")
        assertEquals(1, hesapSayisi(cfg))
    }

    @Test
    fun `ayni cihaz girisi donusumden sonra da ayni hesaba duser`() = testApplication {
        val cfg = config("at3")
        setup(cfg)
        val cihaz = cihazGirisi()
        postJson("/auth/attach", """{"email":"ali@ornek.com","password":"sifre12345"}""", cihaz.accessToken)

        val tekrar = cihazGirisi()
        assertEquals(cihaz.account.id, tekrar.account.id)
        assertEquals("ali@ornek.com", tekrar.account.email, "cihaz girisi artik kalici hesabi doner")
        assertEquals(1, hesapSayisi(cfg))
    }

    @Test
    fun `token yoksa reddedilir`() = testApplication {
        setup(config("at4"))
        assertEquals(
            HttpStatusCode.Unauthorized,
            postJson("/auth/attach", """{"email":"ali@ornek.com","password":"sifre12345"}""").status
        )
    }

    @Test
    fun `hesapta zaten email varsa reddedilir`() = testApplication {
        val cfg = config("at5")
        setup(cfg)
        val kayit = postJson("/auth/register", """{"email":"ali@ornek.com","password":"sifre12345"}""")
            .decode<TokenResponse>()

        val sonuc = postJson(
            "/auth/attach",
            """{"email":"baska@ornek.com","password":"sifre12345"}""",
            kayit.accessToken
        )
        assertEquals(HttpStatusCode.Conflict, sonuc.status)
        assertEquals(1, hesapSayisi(cfg))
    }

    @Test
    fun `email baskasinda ise reddedilir`() = testApplication {
        val cfg = config("at6")
        setup(cfg)
        postJson("/auth/register", """{"email":"ali@ornek.com","password":"sifre12345"}""")
        val cihaz = cihazGirisi()

        val sonuc = postJson(
            "/auth/attach",
            """{"email":"ali@ornek.com","password":"sifre12345"}""",
            cihaz.accessToken
        )
        assertEquals(HttpStatusCode.Conflict, sonuc.status)
        assertEquals(2, hesapSayisi(cfg), "cihaz hesabi bozulmadan duruyor")
    }

    @Test
    fun `gecersiz email ve kisa sifre reddedilir`() = testApplication {
        setup(config("at7"))
        val cihaz = cihazGirisi()

        assertEquals(
            HttpStatusCode.BadRequest,
            postJson("/auth/attach", """{"email":"gecersiz","password":"sifre12345"}""", cihaz.accessToken).status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            postJson("/auth/attach", """{"email":"ali@ornek.com","password":"kisa"}""", cihaz.accessToken).status
        )
    }

    @Test
    fun `donusum onLogin olayini ATTACH olarak tetikler`() = testApplication {
        val cfg = config("at8")
        setup(cfg)
        val cihaz = cihazGirisi()
        olaylar.clear()

        postJson("/auth/attach", """{"email":"ali@ornek.com","password":"sifre12345"}""", cihaz.accessToken)

        val olay = olaylar.single()
        assertEquals(LoginMethod.ATTACH, olay.method)
        assertEquals(cihaz.account.id, olay.account.id)
    }

    @Test
    fun `kayit ve giris istekleri cihaz kimligini olaya tasir`() = testApplication {
        setup(config("at9"))
        olaylar.clear()

        postJson(
            "/auth/register",
            """{"email":"ali@ornek.com","password":"sifre12345","deviceId":"cihaz-9","profile":{"language":"tr"}}"""
        )
        val kayitOlayi = olaylar.single()
        assertEquals(LoginMethod.REGISTER, kayitOlayi.method)
        assertEquals("cihaz-9", kayitOlayi.deviceId, "proje oturumu bu sayede baglayabilir")
        assertNotNull(kayitOlayi.profile)

        olaylar.clear()
        postJson("/auth/login", """{"email":"ali@ornek.com","password":"sifre12345","deviceId":"cihaz-9"}""")
        assertEquals("cihaz-9", olaylar.single().deviceId)
    }

    @Test
    fun `cihaz kimligi gonderilmezse olayda null kalir`() = testApplication {
        setup(config("at10"))
        olaylar.clear()

        postJson("/auth/register", """{"email":"ali@ornek.com","password":"sifre12345"}""")

        assertNull(olaylar.single().deviceId, "eski istemciler bozulmaz")
    }
}

private typealias ApplicationTestBuilderAlias = io.ktor.server.testing.ApplicationTestBuilder
