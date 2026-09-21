package com.furkan.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Kutuphanenin tek giris noktasi. Sadece hesap ve token isleri:
 *
 * ```
 * val auth = AuthConfig(database = db, jwtSecret = System.getenv("AUTH_JWT_SECRET"))
 * auth.migrate()
 * routing { authRoutes(auth) }
 * ```
 *
 * Uc noktalar (basePath'e gore):
 * - `POST {basePath}/register`  email + sifre ile hesap ac
 * - `POST {basePath}/login`     giris
 * - `POST {basePath}/refresh`   token yenile (rotation)
 *
 * Cikis istemcide yapilir: token'lar silinir.
 * Kullaniciya ait oturum/profil bilgisi bu kutuphanenin isi degildir (bkz. user-me-lib).
 */
fun Route.authRoutes(config: AuthConfig) {
    application.installAuthLib(config)

    val handlers = AuthHandlers(
        AccountService(
            config = config,
            accounts = AccountRepository(config.database, config.accounts),
            refreshTokens = RefreshTokenRepository(config.database, config.refreshTokens, config.accounts),
            tokens = TokenService(config)
        )
    )

    route(config.basePath) {
        post("/register") { handlers.register(call) }
        post("/login") { handlers.login(call) }
        post("/refresh") { handlers.refresh(call) }
    }
}

internal class AuthHandlers(private val accounts: AccountService) {

    suspend fun register(call: ApplicationCall) {
        val request = call.receiveOrNull<RegisterRequest>() ?: return
        call.respondResult(
            accounts.register(request.email, request.password, request.displayName),
            HttpStatusCode.Created
        )
    }

    suspend fun login(call: ApplicationCall) {
        val request = call.receiveOrNull<LoginRequest>() ?: return
        call.respondResult(accounts.login(request.email, request.password), HttpStatusCode.OK)
    }

    suspend fun refresh(call: ApplicationCall) {
        val request = call.receiveOrNull<RefreshRequest>() ?: return
        call.respondResult(accounts.refresh(request.refreshToken), HttpStatusCode.OK)
    }

    private suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = try {
        receive<T>()
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, AuthErrorResponse(error = "Gecersiz istek govdesi"))
        null
    }

    private suspend inline fun <reified T : Any> ApplicationCall.respondResult(
        result: AuthResult<T>,
        successStatus: HttpStatusCode
    ) {
        when (result) {
            is AuthResult.Ok -> respond(successStatus, result.value)
            is AuthResult.Fail -> respond(result.status, AuthErrorResponse(error = result.message))
        }
    }
}
