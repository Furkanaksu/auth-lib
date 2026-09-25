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
    val email: String?,
    /** Sadece sosyal giris ile acilmis hesaplarda null. */
    val passwordHash: String?,
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

    /**
     * Hesap acar. [passwordHash] null ise hesap sadece sosyal giris ile kullanilabilir,
     * [email] null ise saglayici email vermemistir.
     */
    fun create(email: String?, passwordHash: String?, displayName: String?): AccountResponse =
        transaction(database) {
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

    fun findRecordById(id: Int): AccountRecord? = transaction(database) {
        table.selectAll().where { table.id eq id }.limit(1).firstOrNull()?.toRecord()
    }

    /**
     * Var olan bir hesaba email ve sifre ekler: cihaz hesabini kaliciya cevirir.
     * Yeni hesap ACMAZ; hesabin id'si korunur, dolayisiyla oturum, gecmis, premium ve
     * ona bagli her sey yerinde kalir.
     */
    fun attachCredentials(id: Int, email: String, passwordHash: String, displayName: String?): Unit =
        transaction(database) {
            table.update({ table.id eq id }) {
                it[this.email] = email
                it[this.passwordHash] = passwordHash
                if (displayName != null) it[this.displayName] = displayName
                it[this.lastLoginAt] = LocalDateTime.now()
            }
        }

    fun touchLogin(id: Int): Unit = transaction(database) {
        table.update({ table.id eq id }) { it[this.lastLoginAt] = LocalDateTime.now() }
    }

    /** Saglayicidan gelen ad, hesapta bos ise doldurulur; var olan ad ezilmez. */
    fun fillDisplayNameIfMissing(id: Int, displayName: String?): Unit = transaction(database) {
        if (displayName.isNullOrBlank()) return@transaction
        val current = table.selectAll().where { table.id eq id }.limit(1).firstOrNull()
        if (current != null && current[table.displayName].isNullOrBlank()) {
            table.update({ table.id eq id }) { it[this.displayName] = displayName.take(100) }
        }
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
