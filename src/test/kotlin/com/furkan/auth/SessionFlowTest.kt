package com.furkan.auth

import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.basic
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionFlowTest {

    @Test
    fun `giris yoksa oturum deviceId'ye baglanir ve her acilista guncellenir`() = testApplication {
        setup(testConfig("s1"))

        val first = postJson(
            "/auth/session",
            """{"deviceId":"cihaz-1","platform":"android","appVersion":"1.0.0","city":"Istanbul",
               "metadata":{"isPremium":false,"theme":"dark"}}"""
        ).decode<SessionResponse>()
        assertNull(first.accountId)
        assertEquals(1, first.openCount)

        // city gonderilmedi -> eski deger korunur; metadata anahtar bazinda birlesir.
        val second = postJson(
            "/auth/session",
            """{"deviceId":"cihaz-1","appVersion":"1.1.0","metadata":{"isPremium":true}}"""
        ).decode<SessionResponse>()
        assertEquals(first.id, second.id, "ayni cihaz ayni oturum")
        assertEquals(2, second.openCount)
        assertEquals("1.1.0", second.appVersion)
        assertEquals("Istanbul", second.city)
        assertEquals("android", second.platform)
        assertEquals("true", second.metadata["isPremium"]!!.jsonPrimitive.content)
        assertEquals("dark", second.metadata["theme"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mevcut istemci formati kabul edilir - snake_case ve tirnakli koordinat`() = testApplication {
        setup(testConfig("s2"))

        val response = postJson(
            "/auth/session",
            """{"deviceId":"eski-istemci","platform":"ios","app_version":"2.3.0","app_name":"prayapp",
               "latitude":"41.0082","longitude":"28.9784"}"""
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val session = response.decode<SessionResponse>()
        assertEquals("2.3.0", session.appVersion)
        assertEquals("prayapp", session.appName)
        assertEquals(41.0082, session.latitude)
    }

    @Test
    fun `deviceId zorunlu`() = testApplication {
        setup(testConfig("s3"))
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/session", """{"platform":"android"}""").status)
    }

    @Test
    fun `giris yapinca cihazin anonim oturumu hesaba devredilir`() = testApplication {
        val config = testConfig("s4")
        setup(config)

        postJson("/auth/session", """{"deviceId":"cihaz-A","platform":"android"}""")
        val anon = postJson("/auth/session", """{"deviceId":"cihaz-A"}""").decode<SessionResponse>()
        assertEquals(2, anon.openCount)

        val tokens = register("kullanici@example.com")
        val linked = postJson("/auth/session", """{"deviceId":"cihaz-A"}""", token = tokens.accessToken)
            .decode<SessionResponse>()

        assertEquals(anon.id, linked.id, "ayni satir hesaba baglandi, gecmis korundu")
        assertEquals(tokens.account.id, linked.accountId)
        assertEquals(3, linked.openCount)
        assertEquals("android", linked.platform)
        assertEquals(0, anonymousRowCount(config), "anonim satir kalmamali")
    }

    @Test
    fun `giris varsa oturum hesaba aittir, cihaz degisince ayni oturum devam eder`() = testApplication {
        val config = testConfig("s5")
        setup(config)
        val tokens = register("iki-cihaz@example.com")

        val onA = postJson("/auth/session", """{"deviceId":"telefon","platform":"android"}""", tokens.accessToken)
            .decode<SessionResponse>()

        // Tablet giris oncesi anonim kullanilmis.
        postJson("/auth/session", """{"deviceId":"tablet","metadata":{"tabletOnly":true}}""")
        postJson("/auth/session", """{"deviceId":"tablet"}""")

        val onB = postJson("/auth/session", """{"deviceId":"tablet","platform":"ios"}""", tokens.accessToken)
            .decode<SessionResponse>()

        assertEquals(onA.id, onB.id, "hesap basina tek oturum")
        assertEquals("tablet", onB.deviceId, "son kullanilan cihaz")
        assertEquals("ios", onB.platform)
        assertEquals(1 + 2 + 1, onB.openCount, "tabletin anonim acilislari hesaba eklendi")
        assertEquals("true", onB.metadata["tabletOnly"]!!.jsonPrimitive.content)
        assertEquals(0, anonymousRowCount(config))
        assertEquals(1, totalRowCount(config))
    }

    @Test
    fun `gecersiz token'la oturum anonim acilmaz, 401 doner`() = testApplication {
        setup(testConfig("s6"))
        val response = postJson("/auth/session", """{"deviceId":"cihaz"}""", token = "bozuk.token.degeri")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `me ucu hesaba bagli oturumu da gosterir`() = testApplication {
        setup(testConfig("s7"))
        val tokens = register("me@example.com")
        postJson("/auth/session", """{"deviceId":"cihaz","appName":"dizibook"}""", tokens.accessToken)

        val me = client.get("/auth/me") { bearerAuth(tokens.accessToken) }
            .decode<MeResponse>()
        assertEquals("dizibook", me.session?.appName)
    }

    @Test
    fun `admin listesi filtreler ve aktif sayiyi dondurur`() = testApplication {
        setup(testConfig("s8"))
        postJson("/auth/session", """{"deviceId":"d1","appName":"prayapp","platform":"android","language":"tr"}""")
        postJson("/auth/session", """{"deviceId":"d2","appName":"dizibook","platform":"ios","language":"en"}""")
        val tokens = register("bagli@example.com")
        postJson("/auth/session", """{"deviceId":"d3","appName":"prayapp"}""", tokens.accessToken)

        val all = client.get("/auth/sessions").decode<PaginatedSessionResponse>()
        assertEquals(3, all.totalItems)

        val prayapp = client.get("/auth/sessions?appName=prayapp").decode<PaginatedSessionResponse>()
        assertEquals(2, prayapp.totalItems)

        val linked = client.get("/auth/sessions?linked=true").decode<PaginatedSessionResponse>()
        assertEquals(1, linked.totalItems)
        assertEquals("d3", linked.data.single().deviceId)

        val anon = client.get("/auth/sessions?linked=false").decode<PaginatedSessionResponse>()
        assertEquals(2, anon.totalItems)

        val filters = client.get("/auth/sessions/filters").decode<SessionFilterOptionsResponse>()
        assertEquals(listOf("dizibook", "prayapp"), filters.appNames)
        assertEquals(listOf("android", "ios"), filters.platforms)

        val active = client.get("/auth/sessions/active-count").decode<ActiveCountResponse>()
        assertEquals(3, active.activeUsers)
        assertEquals(14, active.days)
    }

    @Test
    fun `adminAuthName verilirse admin uclari projenin kendi auth'uyla korunur`() = testApplication {
        val config = testConfig("s9", adminAuthName = "admin")
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                basic("admin") {
                    validate { if (it.name == "admin" && it.password == "admin-sifre") UserIdPrincipal(it.name) else null }
                }
            }
            routing { authRoutes(config) }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/sessions").status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/auth/sessions") { basicAuth("admin", "admin-sifre") }.status
        )
        // Uygulama uclari admin korumasindan etkilenmez.
        assertEquals(HttpStatusCode.OK, postJson("/auth/session", """{"deviceId":"x"}""").status)
    }

    private fun anonymousRowCount(config: AuthConfig): Long = transaction(config.database) {
        config.sessions.selectAll().where { config.sessions.accountId.isNull() }.count()
    }

    private fun totalRowCount(config: AuthConfig): Long = transaction(config.database) {
        config.sessions.selectAll().count()
    }
}
