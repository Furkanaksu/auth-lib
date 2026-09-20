package com.furkan.auth

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

/** Sifre hash'i dahil ic kayit; disari asla bu haliyle cikmaz. */
internal data class AccountRecord(
    val id: Int,
    val email: String,
    val passwordHash: String,
    val response: AccountResponse
)

internal class AccountRepository(
    private val database: Database,
    private val table: AccountTable
) {

    fun findByEmail(email: String): AccountRecord? = transaction(database) {
        table.selectAll().where { table.email eq email }.limit(1).firstOrNull()?.toRecord()
    }

    fun findById(id: Int): AccountResponse? = transaction(database) {
        table.selectAll().where { table.id eq id }.limit(1).firstOrNull()?.toRecord()?.response
    }

    fun existsByEmail(email: String): Boolean = transaction(database) {
        table.selectAll().where { table.email eq email }.limit(1).any()
    }

    fun create(email: String, passwordHash: String, displayName: String?): AccountResponse = transaction(database) {
        val now = LocalDateTime.now()
        val id = table.insert {
            it[this.email] = email
            it[this.passwordHash] = passwordHash
            it[this.displayName] = displayName
            it[this.createdAt] = now
            it[this.lastLoginAt] = now
        }[table.id].value

        AccountResponse(
            id = id,
            email = email,
            displayName = displayName,
            createdAt = now.toString(),
            lastLoginAt = now.toString()
        )
    }

    fun touchLogin(id: Int): Unit = transaction(database) {
        table.update({ table.id eq id }) { it[this.lastLoginAt] = LocalDateTime.now() }
    }

    private fun ResultRow.toRecord() = AccountRecord(
        id = this[table.id].value,
        email = this[table.email],
        passwordHash = this[table.passwordHash],
        response = AccountResponse(
            id = this[table.id].value,
            email = this[table.email],
            displayName = this[table.displayName],
            createdAt = this[table.createdAt].toString(),
            lastLoginAt = this[table.lastLoginAt]?.toString()
        )
    )
}
