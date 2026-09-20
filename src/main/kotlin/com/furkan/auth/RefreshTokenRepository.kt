package com.furkan.auth

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

internal data class RefreshTokenRecord(
    val accountId: Int,
    val expiresAt: LocalDateTime,
    val revokedAt: LocalDateTime?
)

internal class RefreshTokenRepository(
    private val database: Database,
    private val table: RefreshTokenTable,
    private val accounts: AccountTable
) {

    fun create(accountId: Int, tokenHash: String, expiresAt: LocalDateTime): Unit = transaction(database) {
        table.insert {
            it[this.accountId] = EntityID(accountId, accounts)
            it[this.tokenHash] = tokenHash
            it[this.createdAt] = LocalDateTime.now()
            it[this.expiresAt] = expiresAt
        }
    }

    fun find(tokenHash: String): RefreshTokenRecord? = transaction(database) {
        table.selectAll().where { table.tokenHash eq tokenHash }.limit(1).firstOrNull()?.let {
            RefreshTokenRecord(
                accountId = it[table.accountId].value,
                expiresAt = it[table.expiresAt],
                revokedAt = it[table.revokedAt]
            )
        }
    }

    /** Tek token'i iptal eder; zaten iptal edilmisse false. */
    fun revoke(tokenHash: String): Boolean = transaction(database) {
        table.update({ (table.tokenHash eq tokenHash) and table.revokedAt.isNull() }) {
            it[this.revokedAt] = LocalDateTime.now()
        } > 0
    }

    /** Hesabin tum aktif token'larini iptal eder (token calinma suphesi). */
    fun revokeAll(accountId: Int): Unit = transaction(database) {
        table.update({ (table.accountId eq EntityID(accountId, accounts)) and table.revokedAt.isNull() }) {
            it[this.revokedAt] = LocalDateTime.now()
        }
    }
}
