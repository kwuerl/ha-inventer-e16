// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ble

import java.util.UUID

/** GATT layout of the Easy Connect e16 (docs/protocol.md, Bluetooth LE onboarding). */
object EasyConnectGatt {
    private fun uuid16(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    // Standard Device Information service, readable at any time.
    val MODEL: UUID = uuid16(0x2A24)
    val SERIAL: UUID = uuid16(0x2A25)
    val FIRMWARE: UUID = uuid16(0x2A26)
    val HARDWARE: UUID = uuid16(0x2A27)
    val SOFTWARE: UUID = uuid16(0x2A28)
    val MANUFACTURER: UUID = uuid16(0x2A29)

    val AUTH_SERVICE: UUID = UUID.fromString("e6834e4b-7b3a-48e6-91e4-f1d005f564d3")
    val CONFIG_SERVICE: UUID = UUID.fromString("c119e858-0531-4681-9674-5a11f0e53bb4")
    val PROTOCOL_SERVICE: UUID = UUID.fromString("e6ec2fd8-e888-4eb2-9680-e78ed6ea89e1")

    /**
     * The PSK — note the last two digits: the sibling `…e8af` is a reserved 1-byte value.
     * Readable while pairing mode is open; zeros otherwise.
     */
    val TLS_KEY: UUID = UUID.fromString("638ff62c-3823-4e0f-8179-1695c46ee8ad")

    /** Device ID / claim code such as "ABCDE-FGHIJ" (ASCII); the integration's unique ID. */
    val DEVICE_ID: UUID = UUID.fromString("673de933-963b-4298-bfc1-b0ccdada690a")

    /** 16 bytes used by other transports; *not* the TLS-PSK identity on Wi-Fi. */
    val TLS_IDENTITY: UUID = UUID.fromString("98faf8a5-1ee6-4b0c-911e-dc37bff5206f")

    // The PIN characteristics (4cad343a…, d1ae6b70…) are deliberately never touched: confirming
    // the PIN makes the unit return zeros for key and device ID until pairing mode restarts.

    private val SERVICES = setOf(AUTH_SERVICE, CONFIG_SERVICE, PROTOCOL_SERVICE)
    private val NAME_HINTS = Regex("(?i)easy ?connect|inventer|zircon|\\biv-")

    fun looksLikeEasyConnect(name: String?, advertisedServices: Collection<UUID>): Boolean =
        advertisedServices.any { it in SERVICES } || (name != null && NAME_HINTS.containsMatchIn(name))

    /** Device ID text from the raw characteristic value (NUL-padded ASCII). */
    fun parseDeviceId(raw: ByteArray): String {
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.US_ASCII).trim().takeIf { text -> text.all { it in ' '..'~' } }.orEmpty()
    }
}
