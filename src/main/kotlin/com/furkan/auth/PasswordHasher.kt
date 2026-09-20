package com.furkan.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 ile sifre hash'i. Harici bagimlilik yok.
 *
 * Saklanan bicim: `pbkdf2$<iterasyon>$<salt>$<hash>` — iterasyon sayisi hash'in icinde tutulur,
 * boylece ileride artirilsa bile eski hash'ler dogrulanmaya devam eder.
 */
internal object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, ITERATIONS)
        val enc = Base64.getEncoder()
        return "pbkdf2\$$ITERATIONS\$${enc.encodeToString(salt)}\$${enc.encodeToString(hash)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        return try {
            val salt = Base64.getDecoder().decode(parts[2])
            val expected = Base64.getDecoder().decode(parts[3])
            // Sabit zamanli karsilastirma: zamanlama saldirisina kapali.
            MessageDigest.isEqual(derive(password, salt, iterations), expected)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
