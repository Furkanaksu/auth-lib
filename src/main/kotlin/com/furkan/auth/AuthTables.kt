package com.furkan.auth

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction

/** Email + sifre hesaplari. Uygulama giris kullanmiyorsa bu tablo bos kalir. */
class AccountTable(tableName: String) : IntIdTable(tableName) {
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 100).nullable()
    val createdAt = datetime("created_at")
    val lastLoginAt = datetime("last_login_at").nullable()
}

/**
 * Uygulama oturumlari.
 * - Giris yoksa: satir deviceId'ye aittir, accountId null.
 * - Giris varsa: satir hesaba aittir (hesap basina tek satir), deviceId son kullanilan cihazdir.
 * Hesap silinirse oturum anonim kalir (SET NULL).
 */
class SessionTable(tableName: String, accounts: AccountTable) : IntIdTable(tableName) {
    val deviceId = varchar("device_id", 255).index()
    val accountId = reference("account_id", accounts, onDelete = ReferenceOption.SET_NULL)
        .nullable()
        .uniqueIndex()
    val platform = varchar("platform", 50).nullable()
    val appVersion = varchar("app_version", 50).nullable()
    val appName = varchar("app_name", 100).nullable()
    val language = varchar("language", 10).nullable()
    val city = varchar("city", 255).nullable()
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()

    /** Uygulamaya ozel alanlar (premium, izinler...) JSON nesnesi olarak. */
    val metadata = text("metadata").default("{}")
    val openCount = integer("open_count").default(1)
    val firstSeenAt = datetime("first_seen_at")
    val lastSeenAt = datetime("last_seen_at").index()
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
 */
fun AuthConfig.migrate() = transaction(database) {
    @Suppress("DEPRECATION")
    SchemaUtils.createMissingTablesAndColumns(accounts, sessions, refreshTokens)
}
