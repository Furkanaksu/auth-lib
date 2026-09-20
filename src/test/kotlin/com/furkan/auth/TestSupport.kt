package com.furkan.auth

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database

internal val testJson = Json { ignoreUnknownKeys = true }

internal const val TEST_SECRET = "test-secret-en-az-otuz-iki-karakter-uzunlugunda"

internal fun testConfig(dbName: String, adminAuthName: String? = null) = AuthConfig(
    database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"),
    jwtSecret = TEST_SECRET,
    tablePrefix = "${dbName}_",
    adminAuthName = adminAuthName
).also { it.migrate() }

internal fun ApplicationTestBuilder.setup(config: AuthConfig, extraRoutes: Route.() -> Unit = {}) {
    application {
        install(ContentNegotiation) { json() }
        routing {
            authRoutes(config)
            extraRoutes()
        }
    }
}

internal suspend fun ApplicationTestBuilder.postJson(
    path: String,
    body: String,
    token: String? = null
): HttpResponse = client.post(path) {
    contentType(ContentType.Application.Json)
    token?.let { bearerAuth(it) }
    setBody(body)
}

internal suspend fun ApplicationTestBuilder.register(email: String, password: String = "guclu-sifre-123"): TokenResponse {
    val response = postJson("/auth/register", """{"email":"$email","password":"$password"}""")
    return testJson.decodeFromString(response.bodyAsText())
}

internal suspend inline fun <reified T> HttpResponse.decode(): T = testJson.decodeFromString(bodyAsText())
