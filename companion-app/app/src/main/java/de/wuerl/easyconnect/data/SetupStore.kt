// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.data

import de.wuerl.easyconnect.device.AccessKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** The paired Easy Connect: what was read over BLE plus where it lives on the LAN. */
data class PairedUnit(
    val bleAddress: String,
    val bleName: String?,
    val deviceId: String,
    val key: ByteArray,
    val model: String?,
    val serial: String?,
    val firmware: String?,
    val pairedAt: Long,
    val host: String? = null,
    val port: Int = 47820,
    val hostVerified: Boolean = false,
    val zones: List<String> = emptyList(),
) {
    /** Changes whenever anything Home Assistant needs changes. */
    val fingerprint: String
        get() = "$deviceId|$host|$port|" + MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }.take(16)

    override fun equals(other: Any?) = other is PairedUnit && fingerprint == other.fingerprint &&
        bleAddress == other.bleAddress && zones == other.zones && hostVerified == other.hostVerified

    override fun hashCode() = fingerprint.hashCode()
}

data class HaSession(
    val baseUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userName: String? = null,
    val isAdmin: Boolean? = null,
    val locationName: String? = null,
    val version: String? = null,
)

/** The last successful integration setup, so it isn't repeated on every launch. */
data class IntegrationRecord(val fingerprint: String, val domain: String, val title: String?, val outcome: String, val at: Long)

/** Persists the setup state; every record is stored encrypted as one JSON blob. */
class SetupStore(private val blobs: BlobStore) {
    private val json = Json

    fun loadUnit(): PairedUnit? = read(UNIT)?.let { o ->
        PairedUnit(
            bleAddress = o.s("bleAddress") ?: return null,
            bleName = o.s("bleName"),
            deviceId = o.s("deviceId") ?: return null,
            key = o.s("key")?.let(::hexToBytes) ?: return null,
            model = o.s("model"),
            serial = o.s("serial"),
            firmware = o.s("firmware"),
            pairedAt = (o["pairedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            host = o.s("host"),
            port = (o["port"] as? JsonPrimitive)?.intOrNull ?: 47820,
            hostVerified = (o["hostVerified"] as? JsonPrimitive)?.booleanOrNull ?: false,
            zones = (o["zones"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        )
    }

    fun saveUnit(unit: PairedUnit?) = write(UNIT, unit?.let {
        buildJsonObject {
            put("bleAddress", it.bleAddress)
            put("bleName", it.bleName)
            put("deviceId", it.deviceId)
            put("key", AccessKey.hex(it.key))
            put("model", it.model)
            put("serial", it.serial)
            put("firmware", it.firmware)
            put("pairedAt", it.pairedAt)
            put("host", it.host)
            put("port", it.port)
            put("hostVerified", it.hostVerified)
            put("zones", JsonArray(it.zones.map(::JsonPrimitive)))
        }
    })

    fun loadHa(): HaSession? = read(HA)?.let { o ->
        HaSession(
            baseUrl = o.s("baseUrl") ?: return null,
            accessToken = o.s("accessToken") ?: return null,
            refreshToken = o.s("refreshToken") ?: return null,
            expiresAt = (o["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            userName = o.s("userName"),
            isAdmin = (o["isAdmin"] as? JsonPrimitive)?.booleanOrNull,
            locationName = o.s("locationName"),
            version = o.s("version"),
        )
    }

    fun saveHa(session: HaSession?) = write(HA, session?.let {
        buildJsonObject {
            put("baseUrl", it.baseUrl)
            put("accessToken", it.accessToken)
            put("refreshToken", it.refreshToken)
            put("expiresAt", it.expiresAt)
            put("userName", it.userName)
            it.isAdmin?.let { admin -> put("isAdmin", admin) }
            put("locationName", it.locationName)
            put("version", it.version)
        }
    })

    /** The last Home Assistant address, kept after sign-out to prefill the picker. */
    fun lastHaUrl(): String? = read(LAST_URL)?.s("url")
    fun saveLastHaUrl(url: String) = write(LAST_URL, buildJsonObject { put("url", url) })

    fun loadIntegration(): IntegrationRecord? = read(INTEGRATION)?.let { o ->
        IntegrationRecord(
            fingerprint = o.s("fingerprint") ?: return null,
            domain = o.s("domain") ?: return null,
            title = o.s("title"),
            outcome = o.s("outcome") ?: "created",
            at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }

    fun saveIntegration(record: IntegrationRecord?) = write(INTEGRATION, record?.let {
        buildJsonObject {
            put("fingerprint", it.fingerprint)
            put("domain", it.domain)
            put("title", it.title)
            put("outcome", it.outcome)
            put("at", it.at)
        }
    })

    private fun read(name: String): JsonObject? =
        blobs.read(name)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun write(name: String, value: JsonObject?) = blobs.write(name, value?.toString())

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun hexToBytes(hex: String): ByteArray? = runCatching {
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    private companion object {
        const val UNIT = "unit"
        const val HA = "ha"
        const val LAST_URL = "ha_last_url"
        const val INTEGRATION = "integration"
    }
}
