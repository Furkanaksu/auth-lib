package com.furkan.auth

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

/** Cihaz girisi de bir kimlik saglayicisidir; sosyal saglayicilarla ayni tabloda tutulur. */
internal const val DEVICE_PROVIDER = "DEVICE"

/** Cihaz kimligi: hangi hesaba ait ve (varsa) sirrin ozeti. */
internal data class DeviceIdentity(val accountId: Int, val secretHash: String?)

internal class AccountIdentityRepository(
    private val database: Database,
    private val table: AccountIdentityTable,
    private val accounts: AccountTable
) {

    /** Saglayici + saglayici kullanici id'si ile hesabi bulur. Email degisse bile calisir. */
    fun findAccountId(provider: SocialProvider, providerUserId: String): Int? = transaction(database) {
        table.selectAll()
            .where { (table.provider eq provider.name) and (table.providerUserId eq providerUserId) }
            .limit(1)
            .firstOrNull()
            ?.get(table.accountId)
            ?.value
    }

    /** Cihaz kimligini deviceId ile bulur. */
    fun findDevice(deviceId: String): DeviceIdentity? = transaction(database) {
        table.selectAll()
            .where { (table.provider eq DEVICE_PROVIDER) and (table.providerUserId eq deviceId) }
            .limit(1)
            .firstOrNull()
            ?.let { DeviceIdentity(it[table.accountId].value, it[table.secretHash]) }
    }

    fun linkDevice(accountId: Int, deviceId: String, secretHash: String?): Unit = transaction(database) {
        table.insert {
            it[this.accountId] = EntityID(accountId, accounts)
            it[this.provider] = DEVICE_PROVIDER
            it[this.providerUserId] = deviceId
            it[this.secretHash] = secretHash
            it[this.createdAt] = LocalDateTime.now()
        }
    }

    fun link(accountId: Int, identity: SocialIdentity): Unit = transaction(database) {
        table.insert {
            it[this.accountId] = EntityID(accountId, accounts)
            it[this.provider] = identity.provider.name
            it[this.providerUserId] = identity.providerUserId
            it[this.email] = identity.email
            it[this.createdAt] = LocalDateTime.now()
        }
    }

    /** Saglayicidaki email degismis olabilir; bilgi amacli guncel tutulur. */
    fun updateEmail(provider: SocialProvider, providerUserId: String, email: String?): Unit =
        transaction(database) {
            table.update({ (table.provider eq provider.name) and (table.providerUserId eq providerUserId) }) {
                it[this.email] = email
            }
        }

    fun providersOf(accountId: Int): List<SocialProvider> = transaction(database) {
        table.selectAll()
            .where { table.accountId eq EntityID(accountId, accounts) }
            .mapNotNull { SocialProvider.fromOrNull(it[table.provider]) }
    }
}
