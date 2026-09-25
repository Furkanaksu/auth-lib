package com.furkan.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
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
 * - `POST {basePath}/device`             kullanici adi/sifre olmadan cihaz girisi
 * - `POST {basePath}/register`           email + sifre ile hesap ac
 * - `POST {basePath}/login`              giris
 * - `POST {basePath}/refresh`            token yenile (rotation)
 * - `POST {basePath}/social/{provider}`  Google / Apple / Facebook ile giris
 * - `POST {basePath}/attach`            TOKEN ILE: cihaz hesabina email+sifre ekler
 *
 * Cikis istemcide yapilir: token'lar silinir.
 * Kullaniciya ait oturum/profil bilgisi bu kutuphanenin isi degildir (bkz. user-me-lib).
 */
fun Route.authRoutes(config: AuthConfig) {
    application.installAuthLib(config)

    val handlers = AuthHandlers(
        config,
        AccountService(
            config = config,
            accounts = AccountRepository(config.database, config.accounts),
            identities = AccountIdentityRepository(config.database, config.identities, config.accounts),
            refreshTokens = RefreshTokenRepository(config.database, config.refreshTokens, config.accounts),
            tokens = TokenService(config),
            socialVerifier = config.social.verifier ?: DefaultSocialTokenVerifier(config.social)
        )
    )

    route(config.basePath) {
        post("/device") { handlers.deviceLogin(call) }
        post("/register") { handlers.register(call) }
        post("/login") { handlers.login(call) }
        post("/refresh") { handlers.refresh(call) }
        post("/social/{provider}") { handlers.socialLogin(call) }

        // Cihaz hesabini kaliciya cevirme: token zorunlu, hesap token'dan okunur.
        authenticate(config.authName) {
            post("/attach") { handlers.attach(call) }
        }
    }
}

internal class AuthHandlers(
    private val config: AuthConfig,
    private val accounts: AccountService
) {

    suspend fun deviceLogin(call: ApplicationCall) {
        val request = call.receiveOrNull<DeviceLoginRequest>() ?: return
        val result = accounts.deviceLogin(request.deviceId, request.deviceSecret)
        if (result is AuthResult.Ok) {
            config.onLogin(
                LoginEvent(
                    account = result.value.account,
                    method = LoginMethod.DEVICE,
                    deviceId = request.deviceId?.trim(),
                    profile = request.profile
                )
            )
        }
        call.respondResult(result, HttpStatusCode.OK)
    }

    suspend fun register(call: ApplicationCall) {
        val request = call.receiveOrNull<RegisterRequest>() ?: return
        val result = accounts.register(request.email, request.password, request.displayName)
        if (result is AuthResult.Ok) {
            config.onLogin(
                LoginEvent(
                    account = result.value.account,
                    method = LoginMethod.REGISTER,
                    deviceId = request.deviceId?.trim()?.ifBlank { null },
                    profile = request.profile
                )
            )
        }
        call.respondResult(result, HttpStatusCode.Created)
    }

    suspend fun login(call: ApplicationCall) {
        val request = call.receiveOrNull<LoginRequest>() ?: return
        val result = accounts.login(request.email, request.password)
        if (result is AuthResult.Ok) {
            config.onLogin(
                LoginEvent(
                    account = result.value.account,
                    method = LoginMethod.PASSWORD,
                    deviceId = request.deviceId?.trim()?.ifBlank { null },
                    profile = request.profile
                )
            )
        }
        call.respondResult(result, HttpStatusCode.OK)
    }

    /** Token'la gelen hesaba email+sifre ekler; yeni hesap acilmaz. */
    suspend fun attach(call: ApplicationCall) {
        val hesap = call.currentAccount() ?: return call.respond(
            HttpStatusCode.Unauthorized,
            AuthErrorResponse(error = "Gecersiz ya da suresi dolmus token")
        )
        val request = call.receiveOrNull<AttachRequest>() ?: return

        val result = accounts.attach(hesap.accountId, request.email, request.password, request.displayName)
        if (result is AuthResult.Ok) {
            config.onLogin(LoginEvent(result.value.account, LoginMethod.ATTACH))
        }
        call.respondResult(result, HttpStatusCode.OK)
    }

    suspend fun refresh(call: ApplicationCall) {
        val request = call.receiveOrNull<RefreshRequest>() ?: return
        call.respondResult(accounts.refresh(request.refreshToken), HttpStatusCode.OK)
    }

    suspend fun socialLogin(call: ApplicationCall) {
        val provider = SocialProvider.fromOrNull(call.parameters["provider"])
        if (provider == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthErrorResponse(error = "Gecersiz saglayici. Gecerli degerler: GOOGLE, APPLE, FACEBOOK")
            )
            return
        }

        val request = call.receiveOrNull<SocialLoginRequest>() ?: return
        val result = accounts.socialLogin(provider, request.token, request.displayName)
        if (result is AuthResult.Ok) {
            config.onLogin(LoginEvent(result.value.account, LoginMethod.SOCIAL))
        }
        call.respondResult(result, HttpStatusCode.OK)
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
