package com.furkan.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

/** Gecerli access token'la gelen istegin sahibi. */
data class AccountPrincipal(
    val accountId: Int,
    /** Sosyal giris ile acilmis bazi hesaplarda email olmayabilir. */
    val email: String?
)

/** Giris yapmis hesap; token yoksa ya da optional bir route'taysan null. */
fun ApplicationCall.currentAccount(): AccountPrincipal? = principal<AccountPrincipal>()

/**
 * Kutuphanenin JWT provider'ini ([AuthConfig.authName]) kaydeder.
 *
 * [authRoutes] bunu zaten cagirir; sadece KENDI route'larini authRoutes'tan once
 * `authenticate(AUTH_LIB)` ile tanimliyorsan en basta sen cagir. Ayni uygulamada tekrar
 * cagirmak guvenlidir.
 */
fun Application.installAuthLib(config: AuthConfig) {
    val key = AttributeKey<Boolean>("auth-lib-installed:${config.authName}")
    if (attributes.contains(key)) return

    val verifier = TokenService(config).verifier
    authentication {
        jwt(config.authName) {
            realm = config.jwtIssuer
            verifier(verifier)
            validate { credential ->
                val id = credential.payload.subject?.toIntOrNull()
                val email = credential.payload.getClaim(TokenService.CLAIM_EMAIL).asString()
                if (id != null) AccountPrincipal(id, email) else null
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthErrorResponse(error = "Gecersiz ya da suresi dolmus token")
                )
            }
        }
    }
    attributes.put(key, true)
}
