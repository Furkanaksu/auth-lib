package com.furkan.auth

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kullanici adi/sifre olmadan, uygulama acilisinda arka planda yapilan giris. */
class DeviceLoginTest {

    /** onLogin ile projeye iletilen giris olaylari. */
    private val olaylar = mutableListOf<LoginEvent>()

    private fun config(dbName: String) = AuthConfig(
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"),
        jwtSecret = TEST_SECRET,
        tablePrefix = "${dbName}_",
        onLogin = { olaylar += it }
    ).also { it.migrate() }

    @Test
    fun `ilk cagri hesap acar, ikinci cagri ayni hesaba dusr`() = testApplication {
        val cfg = config("d1")
        setup(cfg)

        val ilk = postJson("/auth/device", """{"deviceId":"cihaz-1"}""")
        assertEquals(HttpStatusCode.OK, ilk.status)
        val a = ilk.decode<TokenResponse>()
        assertNull(a.account.email, "cihaz hesabinin emaili yoktur")

        val ikinci = postJson("/auth/device", """{"deviceId":"cihaz-1"}""").decode<TokenResponse>()
        assertEquals(a.account.id, ikinci.account.id, "ayni cihaz ayni hesap")
        assertNotEquals(a.refreshToken, ikinci.refreshToken)
        assertEquals(1, accountCount(cfg))
    }

    @Test
    fun `farkli cihazlar farkli hesap alir`() = testApplication {
        val cfg = config("d2")
        setup(cfg)

        val bir = postJson("/auth/device", """{"deviceId":"cihaz-1"}""").decode<TokenResponse>()
        val iki = postJson("/auth/device", """{"deviceId":"cihaz-2"}""").decode<TokenResponse>()

        assertNotEquals(bir.account.id, iki.account.id)
        assertEquals(2, accountCount(cfg))
    }

    @Test
    fun `token korumali route'da calisir`() = testApplication {
        setup(config("d3"))
        val tokens = postJson("/auth/device", """{"deviceId":"cihaz"}""").decode<TokenResponse>()

        val response = client.get(PROTECTED_PATH) { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `sir verildiyse sonraki girislerde zorunlu olur`() = testApplication {
        setup(config("d4"))

        val ilk = postJson("/auth/device", """{"deviceId":"cihaz","deviceSecret":"gizli-deger"}""")
            .decode<TokenResponse>()

        // Dogru sir: ayni hesap.
        val dogru = postJson("/auth/device", """{"deviceId":"cihaz","deviceSecret":"gizli-deger"}""")
        assertEquals(HttpStatusCode.OK, dogru.status)
        assertEquals(ilk.account.id, dogru.decode<TokenResponse>().account.id)

        // Yanlis sir ve sirsiz istek: reddedilir.
        assertEquals(
            HttpStatusCode.Unauthorized,
            postJson("/auth/device", """{"deviceId":"cihaz","deviceSecret":"yanlis"}""").status
        )
        assertEquals(HttpStatusCode.Unauthorized, postJson("/auth/device", """{"deviceId":"cihaz"}""").status)
    }

    @Test
    fun `sirsiz kaydedilen cihaz sirsiz girmeye devam eder`() = testApplication {
        setup(config("d5"))
        val ilk = postJson("/auth/device", """{"deviceId":"cihaz"}""").decode<TokenResponse>()

        val ikinci = postJson("/auth/device", """{"deviceId":"cihaz","deviceSecret":"sonradan"}""")
        assertEquals(HttpStatusCode.OK, ikinci.status, "sonradan gonderilen sir girisi engellemez")
        assertEquals(ilk.account.id, ikinci.decode<TokenResponse>().account.id)
    }

    @Test
    fun `deviceId zorunlu`() = testApplication {
        setup(config("d6"))
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/device", """{}""").status)
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/device", """{"deviceId":"  "}""").status)
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/device", "bozuk json").status)
    }

    @Test
    fun `profil nesnesi projeye oldugu gibi iletilir`() = testApplication {
        val cfg = config("d7")
        setup(cfg)
        olaylar.clear()

        val tokens = postJson(
            "/auth/device",
            """{"deviceId":"cihaz","profile":{"platform":"android","appVersion":"2.3.0","language":"tr"}}"""
        ).decode<TokenResponse>()

        assertEquals(1, olaylar.size)
        val olay = olaylar.single()
        val profile = olay.profile
        assertEquals(tokens.account.id, olay.account.id)
        assertEquals(LoginMethod.DEVICE, olay.method)
        assertEquals("cihaz", olay.deviceId, "proje cihazi bu alandan ogrenir")
        assertEquals("android", profile!!["platform"]!!.jsonPrimitive.content)
        assertEquals("2.3.0", profile["appVersion"]!!.jsonPrimitive.content)
        assertEquals("tr", profile["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun `profil gonderilmezse geri cagri null ile calisir`() = testApplication {
        val cfg = config("d8")
        setup(cfg)
        olaylar.clear()

        postJson("/auth/device", """{"deviceId":"cihaz"}""")

        assertEquals(1, olaylar.size)
        assertNull(olaylar.single().profile)
    }

    @Test
    fun `cihaz hesabi refresh ile yenilenebilir`() = testApplication {
        setup(config("d9"))
        val ilk = postJson("/auth/device", """{"deviceId":"cihaz"}""").decode<TokenResponse>()

        val yeni = postJson("/auth/refresh", """{"refreshToken":"${ilk.refreshToken}"}""")
        assertEquals(HttpStatusCode.OK, yeni.status)
        assertEquals(ilk.account.id, yeni.decode<TokenResponse>().account.id)
    }

    @Test
    fun `cihaz hesabi kimlik tablosunda DEVICE saglayicisiyla tutulur`() = testApplication {
        val cfg = config("d10")
        setup(cfg)
        postJson("/auth/device", """{"deviceId":"cihaz-x"}""")

        val kayit = transaction(cfg.database) {
            cfg.identities.selectAll().single().let {
                it[cfg.identities.provider] to it[cfg.identities.providerUserId]
            }
        }
        assertEquals("DEVICE" to "cihaz-x", kayit)
        assertTrue(olaylar.isNotEmpty())
    }

    private fun accountCount(config: AuthConfig): Long =
        transaction(config.database) { config.accounts.selectAll().count() }
}
