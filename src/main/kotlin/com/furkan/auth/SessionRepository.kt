package com.furkan.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

/** Istekten gelen oturum alanlari. null alan = mevcut deger korunur. */
internal data class SessionData(
    val platform: String?,
    val appVersion: String?,
    val appName: String?,
    val language: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
    val metadata: JsonObject?
)

internal data class SessionQuery(
    val q: String? = null,
    val appName: String? = null,
    val platform: String? = null,
    val language: String? = null,
    val appVersion: String? = null,
    val since: LocalDateTime? = null,
    /** true: sadece hesaba bagli, false: sadece anonim, null: hepsi */
    val linked: Boolean? = null
)

internal class SessionRepository(
    private val database: Database,
    private val table: SessionTable,
    private val accounts: AccountTable
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Giris yok: oturum deviceId'ye aittir. */
    fun upsertAnonymous(deviceId: String, data: SessionData): SessionResponse = transaction(database) {
        val now = LocalDateTime.now()
        val existing = table.selectAll()
            .where { (table.deviceId eq deviceId) and table.accountId.isNull() }
            .forUpdate()
            .firstOrNull()

        if (existing == null) {
            val id = table.insert {
                it[this.deviceId] = deviceId
                it[this.accountId] = null
                it.writeData(data, previous = null)
                it[this.openCount] = 1
                it[this.firstSeenAt] = now
                it[this.lastSeenAt] = now
            }[table.id].value
            findById(id)!!
        } else {
            val id = existing[table.id].value
            table.update({ table.id eq id }) {
                it.writeData(data, previous = existing)
                it[this.openCount] = existing[table.openCount] + 1
                it[this.lastSeenAt] = now
            }
            findById(id)!!
        }
    }

    /**
     * Giris var: oturum hesaba aittir (hesap basina tek satir), deviceId son kullanilan cihaz olur.
     * Cihazin o ana kadarki anonim oturumu hesaba devredilir:
     * - hesabin henuz oturumu yoksa anonim satir hesaba baglanir (gecmis korunur),
     * - varsa anonim satirin acilis sayisi ve ilk gorulme tarihi hesaba eklenir, anonim satir silinir.
     */
    fun upsertForAccount(accountId: Int, deviceId: String, data: SessionData): SessionResponse =
        transaction(database) {
            val now = LocalDateTime.now()
            val accountRef = EntityID(accountId, accounts)

            val accountSession = table.selectAll()
                .where { table.accountId eq accountRef }
                .forUpdate()
                .firstOrNull()
            val anonymous = table.selectAll()
                .where { (table.deviceId eq deviceId) and table.accountId.isNull() }
                .forUpdate()
                .firstOrNull()

            val id = when {
                accountSession != null -> {
                    val id = accountSession[table.id].value
                    val firstSeen = listOfNotNull(accountSession[table.firstSeenAt], anonymous?.get(table.firstSeenAt)).min()
                    val extraOpens = anonymous?.get(table.openCount) ?: 0
                    val base = anonymous?.let { mergeMetadata(it.metadata(), accountSession.metadata()) }
                    table.update({ table.id eq id }) {
                        it[this.deviceId] = deviceId
                        it.writeData(data, previous = accountSession, baseMetadata = base)
                        it[this.openCount] = accountSession[table.openCount] + extraOpens + 1
                        it[this.firstSeenAt] = firstSeen
                        it[this.lastSeenAt] = now
                    }
                    if (anonymous != null) {
                        val anonId = anonymous[table.id].value
                        table.deleteWhere { b -> b.run { table.id eq anonId } }
                    }
                    id
                }

                anonymous != null -> {
                    val id = anonymous[table.id].value
                    table.update({ table.id eq id }) {
                        it[this.accountId] = accountRef
                        it.writeData(data, previous = anonymous)
                        it[this.openCount] = anonymous[table.openCount] + 1
                        it[this.lastSeenAt] = now
                    }
                    id
                }

                else -> table.insert {
                    it[this.deviceId] = deviceId
                    it[this.accountId] = accountRef
                    it.writeData(data, previous = null)
                    it[this.openCount] = 1
                    it[this.firstSeenAt] = now
                    it[this.lastSeenAt] = now
                }[table.id].value
            }
            findById(id)!!
        }

    fun findByAccount(accountId: Int): SessionResponse? = transaction(database) {
        table.selectAll()
            .where { table.accountId eq EntityID(accountId, accounts) }
            .limit(1)
            .firstOrNull()
            ?.toResponse()
    }

    fun query(filter: SessionQuery, page: Int, size: Int): Pair<List<SessionResponse>, Long> =
        transaction(database) {
            val condition = buildCondition(filter)
            val base = { if (condition != null) table.selectAll().where(condition) else table.selectAll() }

            val total = base().count()
            val items = base()
                .orderBy(table.lastSeenAt, SortOrder.DESC)
                .limit(size).offset(((page - 1).coerceAtLeast(0).toLong()) * size)
                .map { it.toResponse() }
            items to total
        }

    fun distinctFilterValues(): SessionFilterOptionsResponse = transaction(database) {
        fun distinct(column: Column<String?>): List<String> =
            table.select(column).withDistinct()
                .mapNotNull { it[column]?.takeIf(String::isNotBlank) }
                .sorted()

        SessionFilterOptionsResponse(
            appNames = distinct(table.appName),
            platforms = distinct(table.platform),
            languages = distinct(table.language),
            appVersions = distinct(table.appVersion)
        )
    }

    fun countActiveSince(since: LocalDateTime): Long = transaction(database) {
        table.selectAll().where { table.lastSeenAt greaterEq since }.count()
    }

    private fun findById(id: Int): SessionResponse? =
        table.selectAll().where { table.id eq id }.limit(1).firstOrNull()?.toResponse()

    /** null gelen alanlar mevcut degeri korur; metadata anahtar bazinda birlestirilir. */
    private fun UpdateBuilder<*>.writeData(
        data: SessionData,
        previous: ResultRow?,
        baseMetadata: JsonObject? = null
    ) {
        fun <T> pick(new: T?, column: Column<T?>): T? = new ?: previous?.get(column)

        this[table.platform] = pick(data.platform, table.platform)
        this[table.appVersion] = pick(data.appVersion, table.appVersion)
        this[table.appName] = pick(data.appName, table.appName)
        this[table.language] = pick(data.language, table.language)
        this[table.city] = pick(data.city, table.city)
        this[table.latitude] = pick(data.latitude, table.latitude)
        this[table.longitude] = pick(data.longitude, table.longitude)

        val current = baseMetadata ?: previous?.metadata() ?: JsonObject(emptyMap())
        this[table.metadata] = mergeMetadata(current, data.metadata ?: JsonObject(emptyMap())).toString()
    }

    private fun mergeMetadata(current: JsonObject, incoming: JsonObject) = JsonObject(current + incoming)

    private fun ResultRow.metadata(): JsonObject =
        runCatching { json.parseToJsonElement(this[table.metadata]) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())

    private fun ResultRow.toResponse() = SessionResponse(
        id = this[table.id].value,
        deviceId = this[table.deviceId],
        accountId = this[table.accountId]?.value,
        platform = this[table.platform],
        appVersion = this[table.appVersion],
        appName = this[table.appName],
        language = this[table.language],
        city = this[table.city],
        latitude = this[table.latitude],
        longitude = this[table.longitude],
        metadata = metadata(),
        openCount = this[table.openCount],
        firstSeenAt = this[table.firstSeenAt].toString(),
        lastSeenAt = this[table.lastSeenAt].toString()
    )

    private fun buildCondition(f: SessionQuery): (SqlExpressionBuilder.() -> Op<Boolean>)? {
        val parts = mutableListOf<SqlExpressionBuilder.() -> Op<Boolean>>()
        if (!f.q.isNullOrBlank()) {
            val like = "%${f.q.trim().lowercase()}%"
            parts += { (table.deviceId.lowerCase() like like) or (table.city.lowerCase() like like) }
        }
        f.appName?.takeIf(String::isNotBlank)?.let { v -> parts += { table.appName eq v } }
        f.platform?.takeIf(String::isNotBlank)?.let { v -> parts += { table.platform eq v } }
        f.language?.takeIf(String::isNotBlank)?.let { v -> parts += { table.language eq v } }
        f.appVersion?.takeIf(String::isNotBlank)?.let { v -> parts += { table.appVersion eq v } }
        f.since?.let { v -> parts += { table.lastSeenAt greaterEq v } }
        when (f.linked) {
            true -> parts += { table.accountId.isNotNull() }
            false -> parts += { table.accountId.isNull() }
            null -> Unit
        }
        if (parts.isEmpty()) return null
        return { parts.map { it() }.reduce { acc, op -> acc and op } }
    }
}
