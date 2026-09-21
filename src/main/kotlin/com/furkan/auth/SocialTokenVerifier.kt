package com.furkan.auth

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * Saglayicinin token'ini dogrular. Gecersizse null doner.
 *
 * Varsayilan uygulama [DefaultSocialTokenVerifier]; testlerde [SocialConfig.verifier] ile
 * sahte bir dogrulayici verilebilir.
 */
fun interface SocialTokenVerifier {
    suspend fun verify(provider: SocialProvider, token: String): SocialIdentity?
}

/**
 * Google ve Apple: ID token'in imzasi saglayicinin JWKS'i ile dogrulanir, `iss` ve `aud` kontrol edilir.
 * Facebook: access token Graph API'nin debug_token ucuna sorulur, sonra profil cekilir.
 */
class DefaultSocialTokenVerifier(
    private val config: SocialConfig,
    private val httpClient: HttpClient = defaultClient()
) : SocialTokenVerifier {

    private val googleJwks by lazy { jwkProvider("https://www.googleapis.com/oauth2/v3/certs") }
    private val appleJwks by lazy { jwkProvider("https://appleid.apple.com/auth/keys") }

    override suspend fun verify(provider: SocialProvider, token: String): SocialIdentity? = when (provider) {
        SocialProvider.GOOGLE -> verifyGoogle(token)
        SocialProvider.APPLE -> verifyApple(token)
        SocialProvider.FACEBOOK -> verifyFacebook(token)
    }

    private suspend fun verifyGoogle(token: String): SocialIdentity? {
        val jwt = verifyIdToken(
            token = token,
            jwkProvider = googleJwks,
            issuers = setOf("https://accounts.google.com", "accounts.google.com"),
            audiences = config.googleClientIds
        ) ?: return null

        return SocialIdentity(
            provider = SocialProvider.GOOGLE,
            providerUserId = jwt.subject ?: return null,
            email = jwt.getClaim("email").asString(),
            emailVerified = jwt.getClaim("email_verified").asBooleanLenient(),
            displayName = jwt.getClaim("name").asString()
        )
    }

    private suspend fun verifyApple(token: String): SocialIdentity? {
        val jwt = verifyIdToken(
            token = token,
            jwkProvider = appleJwks,
            issuers = setOf("https://appleid.apple.com"),
            audiences = config.appleAudiences
        ) ?: return null

        return SocialIdentity(
            provider = SocialProvider.APPLE,
            providerUserId = jwt.subject ?: return null,
            email = jwt.getClaim("email").asString(),
            // Apple bu alani bazen "true" string'i olarak gonderir.
            emailVerified = jwt.getClaim("email_verified").asBooleanLenient(),
            // Apple ismi token'da hic gondermez; istemci ilk yetkilendirmede ayrica iletir.
            displayName = null
        )
    }

    private suspend fun verifyFacebook(token: String): SocialIdentity? {
        val appId = config.facebookAppId ?: return null
        val appSecret = config.facebookAppSecret ?: return null

        val debug = httpClient.getJsonOrNull(
            "https://graph.facebook.com/debug_token?input_token=$token&access_token=$appId|$appSecret"
        )?.get("data")?.jsonObject ?: return null

        val valid = debug["is_valid"]?.jsonPrimitive?.booleanOrNull == true
        val sameApp = debug["app_id"]?.jsonPrimitive?.content == appId
        if (!valid || !sameApp) return null

        val userId = debug["user_id"]?.jsonPrimitive?.content ?: return null
        val profile = httpClient.getJsonOrNull(
            "https://graph.facebook.com/me?fields=id,name,email&access_token=$token"
        )

        return SocialIdentity(
            provider = SocialProvider.FACEBOOK,
            providerUserId = userId,
            email = profile?.get("email")?.jsonPrimitive?.content,
            // Facebook email'i dogrulanmis kabul edilir; yine de hesap birlestirme icin
            // linkByVerifiedEmail ayari belirleyicidir.
            emailVerified = profile?.get("email") != null,
            displayName = profile?.get("name")?.jsonPrimitive?.content
        )
    }

    private suspend fun verifyIdToken(
        token: String,
        jwkProvider: com.auth0.jwk.JwkProvider,
        issuers: Set<String>,
        audiences: Set<String>
    ): DecodedJWT? = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = JWT.decode(token)
            val key = jwkProvider.get(decoded.keyId).publicKey as RSAPublicKey
            JWT.require(Algorithm.RSA256(key, null))
                .withAnyOfAudience(*audiences.toTypedArray())
                .acceptLeeway(60)
                .build()
                .verify(token)
                .takeIf { it.issuer in issuers }
        }.getOrNull()
    }

    private fun jwkProvider(url: String) = JwkProviderBuilder(URI(url).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    private suspend fun HttpClient.getJsonOrNull(url: String): JsonObject? = runCatching {
        val response: HttpResponse = get(url)
        if (!response.status.isSuccess()) return null
        Json.parseToJsonElement(response.body<String>()).jsonObject
    }.getOrNull()

    private fun com.auth0.jwt.interfaces.Claim.asBooleanLenient(): Boolean =
        asBoolean() ?: (asString()?.equals("true", ignoreCase = true) == true)

    companion object {
        private fun defaultClient() = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }
}

private fun io.ktor.http.HttpStatusCode.isSuccess() = value in 200..299
