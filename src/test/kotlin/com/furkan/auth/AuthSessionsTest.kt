package com.furkan.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** HTTP olmadan, kod uzerinden oturum yonetimi (projeler kendi uclarini koruyabilsin diye). */
class AuthSessionsTest {

    @Test
    fun `koddan upsert calisir ve acilis sayar`() {
        val sessions = AuthSessions(testConfig("api1"))

        val first = sessions.upsert(SessionRequest(deviceId = "cihaz", platform = "android", city = "Istanbul"))
        assertEquals(1, first.openCount)
        assertNull(first.accountId)

        val second = sessions.upsert(SessionRequest(deviceId = "cihaz", appVersion = "2.0.0"))
        assertEquals(first.id, second.id)
        assertEquals(2, second.openCount)
        assertEquals("Istanbul", second.city, "gonderilmeyen alan korunur")
        assertEquals("2.0.0", second.appVersion)
    }

    @Test
    fun `metadata birlestirilir`() {
        val sessions = AuthSessions(testConfig("api2"))
        sessions.upsert(
            SessionRequest(
                deviceId = "cihaz",
                metadata = JsonObject(mapOf("isPremium" to JsonPrimitive(false), "tema" to JsonPrimitive("koyu")))
            )
        )
        val updated = sessions.upsert(
            SessionRequest(deviceId = "cihaz", metadata = JsonObject(mapOf("isPremium" to JsonPrimitive(true))))
        )

        assertEquals("true", updated.metadata["isPremium"]!!.jsonPrimitive.content)
        assertEquals("koyu", updated.metadata["tema"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cihaza gore okunur`() {
        val config = testConfig("api3")
        val sessions = AuthSessions(config)
        sessions.upsert(SessionRequest(deviceId = "cihaz", language = "tr"))

        assertEquals("tr", sessions.findByDevice("cihaz")?.language)
        assertNull(sessions.findByDevice("olmayan"))
    }

    @Test
    fun `deviceId zorunlu`() {
        val sessions = AuthSessions(testConfig("api4"))
        assertFailsWith<IllegalArgumentException> { sessions.upsert(SessionRequest(deviceId = "  ")) }
        assertFailsWith<IllegalArgumentException> { sessions.upsert(SessionRequest(platform = "android")) }
    }
}
