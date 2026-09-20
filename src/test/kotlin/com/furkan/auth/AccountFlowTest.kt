package com.furkan.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountFlowTest {

    @Test
    fun `kayit token doner ve me ucu hesabi gosterir`() = testApplication {
        setup(testConfig("a1"))

        val response = postJson(
            "/auth/register",
            """{"email":"  Ali@Example.COM ","password":"guclu-sifre-123","displayName":"Ali"}"""
        )
        assertEquals(HttpStatusCode.Created, response.status)
        val tokens = response.decode<TokenResponse>()
        assertEquals("ali@example.com", tokens.account.email, "email kucuk harfe ve bosluksuz normalize edilir")
        assertEquals("Bearer", tokens.tokenType)
        assertEquals(3600, tokens.expiresIn)

        val me = client.get("/auth/me") { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("Ali", me.decode<MeResponse>().account.displayName)
    }

    @Test
    fun `ayni email ikinci kez kayit olamaz`() = testApplication {
        setup(testConfig("a2"))
        register("veli@example.com")

        val again = postJson("/auth/register", """{"email":"VELI@example.com","password":"baska-sifre-123"}""")
        assertEquals(HttpStatusCode.Conflict, again.status)
    }

    @Test
    fun `gecersiz email ve kisa sifre reddedilir`() = testApplication {
        setup(testConfig("a3"))

        assertEquals(
            HttpStatusCode.BadRequest,
            postJson("/auth/register", """{"email":"email-degil","password":"guclu-sifre-123"}""").status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            postJson("/auth/register", """{"email":"a@b.com","password":"kisa"}""").status
        )
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/register", "bozuk json").status)
    }

    @Test
    fun `giris dogru sifreyle calisir, yanlis ve bilinmeyen email ayni hatayi verir`() = testApplication {
        setup(testConfig("a4"))
        register("ayse@example.com", "dogru-sifre-123")

        assertEquals(
            HttpStatusCode.OK,
            postJson("/auth/login", """{"email":"AYSE@example.com","password":"dogru-sifre-123"}""").status
        )

        val wrong = postJson("/auth/login", """{"email":"ayse@example.com","password":"yanlis-sifre"}""")
        val unknown = postJson("/auth/login", """{"email":"yok@example.com","password":"dogru-sifre-123"}""")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals(
            wrong.decode<AuthErrorResponse>().error,
            unknown.decode<AuthErrorResponse>().error,
            "email'in kayitli olup olmadigi hata mesajindan anlasilmamali"
        )
    }

    @Test
    fun `refresh yeni token verir, eski refresh tekrar kullanilamaz`() = testApplication {
        setup(testConfig("a5"))
        val first = register("mehmet@example.com")

        val refreshed = postJson("/auth/refresh", """{"refreshToken":"${first.refreshToken}"}""")
        assertEquals(HttpStatusCode.OK, refreshed.status)
        val second = refreshed.decode<TokenResponse>()
        assertNotEquals(first.refreshToken, second.refreshToken)

        val reuse = postJson("/auth/refresh", """{"refreshToken":"${first.refreshToken}"}""")
        assertEquals(HttpStatusCode.Unauthorized, reuse.status)

        // Iptal edilmis token tekrar geldi: calinma suphesi, hesabin tum refresh token'lari iptal.
        val afterReuse = postJson("/auth/refresh", """{"refreshToken":"${second.refreshToken}"}""")
        assertEquals(HttpStatusCode.Unauthorized, afterReuse.status)
    }

    @Test
    fun `bilinmeyen refresh token reddedilir`() = testApplication {
        setup(testConfig("a6"))
        assertEquals(
            HttpStatusCode.Unauthorized,
            postJson("/auth/refresh", """{"refreshToken":"bilinmeyen"}""").status
        )
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/refresh", """{}""").status)
    }

    @Test
    fun `me ucu tokensiz, bozuk ya da baska anahtarla imzali token'i reddeder`() = testApplication {
        setup(testConfig("a7"))

        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { bearerAuth("bozuk.token.degeri") }.status)

        val forged = JWT.create()
            .withIssuer("auth-lib")
            .withAudience("auth-lib-clients")
            .withSubject("1")
            .withClaim("email", "saldirgan@example.com")
            .withClaim("typ", "access")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("baska-bir-anahtar-en-az-otuz-iki-karakter"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { bearerAuth(forged) }.status)
    }

    @Test
    fun `suresi dolmus token reddedilir`() = testApplication {
        setup(testConfig("a8"))

        val expired = JWT.create()
            .withIssuer("auth-lib")
            .withAudience("auth-lib-clients")
            .withSubject("1")
            .withClaim("email", "a@b.com")
            .withClaim("typ", "access")
            .withExpiresAt(Date(System.currentTimeMillis() - 120_000))
            .sign(Algorithm.HMAC256(TEST_SECRET))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { bearerAuth(expired) }.status)
    }

    @Test
    fun `tuketen proje kendi route'unu tek satirla korur`() = testApplication {
        setup(testConfig("a9")) {
            authenticate(AUTH_LIB) {
                get("/profil") {
                    call.respondText("merhaba ${call.currentAccount()!!.email}")
                }
            }
        }
        val tokens = register("proje@example.com")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/profil").status)
        val ok = client.get("/profil") { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("proje@example.com"))
    }

    @Test
    fun `sifre hash'i dogrulanir ve her seferinde farkli tuzlanir`() {
        val a = PasswordHasher.hash("ayni-sifre")
        val b = PasswordHasher.hash("ayni-sifre")
        assertNotEquals(a, b)
        assertTrue(PasswordHasher.verify("ayni-sifre", a))
        assertTrue(!PasswordHasher.verify("farkli-sifre", a))
        assertTrue(!PasswordHasher.verify("ayni-sifre", "bozuk-hash"))
    }
}
