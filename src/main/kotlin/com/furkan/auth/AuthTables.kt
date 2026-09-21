package com.furkan.auth

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Hesaplar.
 *
 * - [email] null olabilir: Apple "email'imi gizle" derse ya da Facebook izin vermezse email gelmez.
 *   Nullable unique kolon birden fazla NULL'a izin verir, dolu degerler tekil kalir.
 * - [passwordHash] null olabilir: sadece sosyal giris ile acilmis hesabin sifresi yoktur.
 */
class AccountTable(tableName: String) : IntIdTable(tableName) {
    val email = varchar("email", 255).nullable().uniqueIndex()
    val passwordHash = varchar("password_hash", 255).nullable()
    val displayName = varchar("display_name", 100).nullable()
    val createdAt = datetime("created_at")
    val lastLoginAt = datetime("last_login_at").nullable()
}

/**
 * Hesabin sosyal kimlikleri. Bir hesap birden fazla saglayiciya baglanabilir
 * (ayni kisi hem Google hem Apple ile girebilir).
 *
 * Eslesme her zaman (provider, providerUserId) ikilisiyle yapilir: email degisse bile sabittir.
 */
class AccountIdentityTable(tableName: String, accounts: AccountTable) : IntIdTable(tableName) {
    val accountId = reference("account_id", accounts, onDelete = ReferenceOption.CASCADE).index()
    val provider = varchar("provider", 20)
    val providerUserId = varchar("provider_user_id", 255)
    /** Saglayicinin o anki email'i; bilgi amacli, eslesme icin kullanilmaz. */
    val email = varchar("email", 255).nullable()
    val createdAt = datetime("created_at")

    init {
        uniqueIndex(provider, providerUserId)
    }
}

/** Refresh token'lar. Token'in kendisi degil, SHA-256 ozeti saklanir. */
class RefreshTokenTable(tableName: String, accounts: AccountTable) : IntIdTable(tableName) {
    val accountId = reference("account_id", accounts, onDelete = ReferenceOption.CASCADE).index()
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")
    val revokedAt = datetime("revoked_at").nullable()
}

/**
 * Tablolari, config'te verilen DB'de olusturur/gunceller.
 * Kutuphane kendiliginden calistirmaz; proje acilista bir kez cagirir.
 *
 * NOT: 2.x'ten gelen bir veritabaninda `email` ve `password_hash` NOT NULL tanimlidir.
 * Exposed bu kisiti kendiliginden gevsetemezse tek seferlik su SQL gerekir:
 * ```
 * ALTER TABLE <prefix>accounts ALTER COLUMN email DROP NOT NULL;
 * ALTER TABLE <prefix>accounts ALTER COLUMN password_hash DROP NOT NULL;
 * ```
 */
fun AuthConfig.migrate() = transaction(database) {
    @Suppress("DEPRECATION")
    SchemaUtils.createMissingTablesAndColumns(accounts, identities, refreshTokens)
}
