package com.furkan.auth

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String? = null,
    val password: String? = null,
    val displayName: String? = null
)

@Serializable
data class LoginRequest(
    val email: String? = null,
    val password: String? = null
)

@Serializable
data class RefreshRequest(
    val refreshToken: String? = null
)

@Serializable
data class AccountResponse(
    val id: Int,
    /** Saglayici email vermediyse (Apple "email'imi gizle", Facebook izni yok) null olabilir. */
    val email: String?,
    val displayName: String?,
    val createdAt: String,
    val lastLoginAt: String? = null
)

/** register / login / refresh cevabi. expiresIn saniye cinsinden access token omrudur. */
@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val account: AccountResponse
)

@Serializable
data class AuthErrorResponse(
    val status: String = "fail",
    val error: String
)
