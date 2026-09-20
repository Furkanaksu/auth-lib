package com.furkan.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.LocalDateTime

/**
 * Kutuphanenin tek giris noktasi.
 *
 * ```
 * val auth = AuthConfig(database = db, jwtSecret = System.getenv("AUTH_JWT_SECRET"))
 * auth.migrate()
 * routing { authRoutes(auth) }
 * ```
 *
 * Uc noktalar (basePath'e gore):
 * - `POST {basePath}/session`                 oturum upsert (token varsa hesaba, yoksa deviceId'ye)
 * - `POST {basePath}/register`                email + sifre ile hesap ac
 * - `POST {basePath}/login`                   giris
 * - `POST {basePath}/refresh`                 token yenile (rotation)
 * - `POST {basePath}/logout`                  refresh token'i iptal et
 * - `GET  {basePath}/me`                      hesap + oturumu (token gerekir)
 * - `GET  {basePath}/sessions`                admin: filtreli + sayfali oturum listesi
 * - `GET  {basePath}/sessions/filters`        admin: dropdown degerleri
 * - `GET  {basePath}/sessions/active-count`   admin: son N gunde aktif oturum sayisi
 */
fun Route.authRoutes(config: AuthConfig) {
    application.installAuthLib(config)

    val tokens = TokenService(config)
    val accountRepository = AccountRepository(config.database, config.accounts)
    val sessionRepository = SessionRepository(config.database, config.sessions, config.accounts)
    val accountService = AccountService(
        config = config,
        accounts = accountRepository,
        refreshTokens = RefreshTokenRepository(config.database, config.refreshTokens, config.accounts),
        tokens = tokens
    )
    val handlers = AuthHandlers(config, accountService, sessionRepository)

    route(config.basePath) {
        // Token opsiyonel: varsa oturum hesaba, yoksa cihaza baglanir. Gecersiz token 401 doner.
        authenticate(config.authName, optional = true) {
            post("/session") { handlers.upsertSession(call) }
        }

        post("/register") { handlers.register(call) }
        post("/login") { handlers.login(call) }
        post("/refresh") { handlers.refresh(call) }
        post("/logout") { handlers.logout(call) }

        authenticate(config.authName) {
            get("/me") { handlers.me(call) }
        }

        adminProtected(config.adminAuthName) {
            get("/sessions") { handlers.listSessions(call) }
            get("/sessions/filters") { handlers.filterOptions(call) }
            get("/sessions/active-count") { handlers.activeCount(call) }
        }
    }
}

/** Admin uclarini projenin kendi auth provider'iyla sarar; provider verilmemisse oldugu gibi birakir. */
private fun Route.adminProtected(adminAuthName: String?, build: Route.() -> Unit) {
    if (adminAuthName != null) authenticate(adminAuthName) { build() } else build()
}

internal class AuthHandlers(
    private val config: AuthConfig,
    private val accounts: AccountService,
    private val sessions: SessionRepository
) {

    suspend fun upsertSession(call: ApplicationCall) {
        val request = call.receiveOrNull<SessionRequest>() ?: return

        val deviceId = request.deviceId?.trim()
        if (deviceId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, AuthErrorResponse(error = "deviceId bos olamaz"))
            return
        }
        if (deviceId.length > 255) {
            call.respond(HttpStatusCode.BadRequest, AuthErrorResponse(error = "deviceId en fazla 255 karakter olabilir"))
            return
        }

        val data = SessionData(
            platform = request.platform?.take(50),
            appVersion = request.appVersion?.take(50),
            appName = request.appName?.take(100),
            language = request.language?.take(10),
            city = request.city?.take(255),
            latitude = request.latitude,
            longitude = request.longitude,
            metadata = request.metadata
        )

        val account = call.currentAccount()
        val session = if (account != null) {
            sessions.upsertForAccount(account.accountId, deviceId, data)
        } else {
            sessions.upsertAnonymous(deviceId, data)
        }
        call.respond(session)
    }

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

    suspend fun logout(call: ApplicationCall) {
        val request = call.receiveOrNull<RefreshRequest>() ?: return
        when (val result = accounts.logout(request.refreshToken)) {
            is AuthResult.Ok -> call.respond(HttpStatusCode.NoContent)
            is AuthResult.Fail -> call.respond(result.status, AuthErrorResponse(error = result.message))
        }
    }

    suspend fun me(call: ApplicationCall) {
        val principal = call.currentAccount()!!
        val account = accounts.findAccount(principal.accountId)
        if (account == null) {
            call.respond(HttpStatusCode.Unauthorized, AuthErrorResponse(error = "Hesap bulunamadi"))
            return
        }
        call.respond(MeResponse(account = account, session = sessions.findByAccount(principal.accountId)))
    }

    suspend fun listSessions(call: ApplicationCall) {
        val params = call.request.queryParameters
        val days = params["days"]?.toIntOrNull()?.takeIf { it > 0 }
        val active = params["active"]?.toBooleanStrictOrNull() == true
        val window = listOfNotNull(days, if (active) config.activeWindowDays else null).minOrNull()

        val linked = when (params["linked"]?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

        val filter = SessionQuery(
            q = params["q"],
            appName = params["appName"],
            platform = params["platform"],
            language = params["language"],
            appVersion = params["appVersion"],
            since = window?.let { LocalDateTime.now().minusDays(it.toLong()) },
            linked = linked
        )

        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val size = (params["size"]?.toIntOrNull() ?: config.defaultPageSize).coerceIn(1, config.maxPageSize)
        val (items, total) = sessions.query(filter, page, size)

        call.respond(
            PaginatedSessionResponse(
                data = items,
                page = page,
                size = size,
                totalItems = total,
                totalPages = if (total == 0L) 1 else ((total + size - 1) / size).toInt()
            )
        )
    }

    suspend fun filterOptions(call: ApplicationCall) {
        call.respond(sessions.distinctFilterValues())
    }

    suspend fun activeCount(call: ApplicationCall) {
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.takeIf { it > 0 } ?: config.activeWindowDays
        val since = LocalDateTime.now().minusDays(days.toLong())
        call.respond(ActiveCountResponse(activeUsers = sessions.countActiveSince(since), days = days, since = since.toString()))
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
