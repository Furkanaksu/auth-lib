package com.furkan.auth

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction

/** Email + sifre hesaplari. */
class AccountTable(tableName: String) : IntIdTable(tableName) {
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 100).nullable()
    val createdAt = datetime("created_at")
    val lastLoginAt = datetime("last_login_at").nullable()
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
    SchemaUtils.createMissingTablesAndColumns(accounts, refreshTokens)
}
