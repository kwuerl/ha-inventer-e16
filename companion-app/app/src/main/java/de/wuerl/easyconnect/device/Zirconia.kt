// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

/**
 * Wire-level constants of the inVENTer "Zirconia" device family (Easy Connect e16 WiFi).
 * See docs/protocol.md in the repository root.
 */
object Zirconia {
    /** TLS-PSK identity: a constant string, not the 16-byte `tlsIdentity` characteristic. */
    const val PSK_IDENTITY = "zirconia"

    /** TCP port of the TLS-PSK control channel ("zirconiaPort"). */
    const val TCP_PORT = 47820

    /** MQTT-over-TLS port; open together with [TCP_PORT] on an e16 — a strong fingerprint. */
    const val MQTT_PORT = 8883

    const val HEADER_LENGTH = 10
    const val BACKTRACK_RECORD_LENGTH = 3

    object PacketType {
        const val ECHO = 6
        const val WIFI_STATUS = 13
        const val DEVICE_VIEW_HEADER = 141
        const val DEVICE_VIEW_ROW = 142
        const val ZONE_VIEW_HEADER = 145
        const val ZONE_VIEW_ROW = 146
    }

    object Operation {
        const val UPDATE = 1
        const val DATA_REQUEST = 2
    }

    object Address {
        const val MASTER = 0
        const val ESP32 = 159
    }
}

/**
 * The app's CRC-8: polynomial 0x07 with the bit test done *after* the shift, computed over
 * `data[from until to]`. For a ProtocolPacketV2 frame that is everything after byte 0.
 */
fun crc8Zirconia(data: ByteArray, from: Int = 1, to: Int = data.size): Int {
    var crc = 0
    for (i in from until to) {
        var x = crc xor (data[i].toInt() and 0xFF)
        repeat(8) {
            x = (x shl 1) and 0xFF
            if (x and 0x80 != 0) x = x xor 0x07
        }
        crc = x
    }
    return crc and 0xFF
}

/** One decoded ProtocolPacketV2 frame (backtracking records stripped). */
class Frame(
    val packetType: Int,
    val operationFlags: Int,
    val finalDestination: Int,
    val payload: ByteArray,
)

object ProtocolPacketV2 {

    fun build(
        packetType: Int,
        operationFlags: Int,
        payload: ByteArray = ByteArray(0),
        finalDestination: Int = Zirconia.Address.MASTER,
    ): ByteArray {
        val length = Zirconia.HEADER_LENGTH + payload.size
        require(length <= 0xFF) { "payload too large for a single frame" }
        val buf = ByteArray(length)
        buf[1] = length.toByte()
        buf[2] = 0 // no backtracking records in app → device requests
        buf[3] = packetType.toByte()
        buf[4] = operationFlags.toByte()
        buf[5] = finalDestination.toByte()
        // bytes 6..9: timestamp, big-endian; 0 is accepted by the unit
        payload.copyInto(buf, Zirconia.HEADER_LENGTH)
        buf[0] = crc8Zirconia(buf, 1, length).toByte()
        return buf
    }

    /** A data request (`operationFlags = DataRequest`), the only kind this app sends. */
    fun dataRequest(packetType: Int, payload: ByteArray = ByteArray(0), finalDestination: Int = Zirconia.Address.MASTER) =
        build(packetType, Zirconia.Operation.DATA_REQUEST, payload, finalDestination)
}

/**
 * Incremental decoder for the TLS byte stream. Frames are `length` bytes (header + payload)
 * followed by `3 × backtrackCount` routing bytes, which are skipped. A frame with a bad CRC
 * makes the decoder drop one byte and re-synchronise.
 */
class FrameDecoder {
    private var buffer = ByteArray(1024)
    private var size = 0

    fun feed(bytes: ByteArray, count: Int = bytes.size) {
        if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
        bytes.copyInto(buffer, size, 0, count)
        size += count
    }

    fun next(): Frame? {
        while (size >= 2) {
            val length = buffer[1].toInt() and 0xFF
            if (length < Zirconia.HEADER_LENGTH) {
                drop(1)
                continue
            }
            if (size < Zirconia.HEADER_LENGTH) return null
            val backtrack = (buffer[2].toInt() and 0xFF).coerceAtMost(MAX_BACKTRACK_RECORDS)
            val total = length + backtrack * Zirconia.BACKTRACK_RECORD_LENGTH
            if (size < total) return null
            if (crc8Zirconia(buffer, 1, length) != (buffer[0].toInt() and 0xFF)) {
                drop(1)
                continue
            }
            val frame = Frame(
                packetType = buffer[3].toInt() and 0xFF,
                operationFlags = buffer[4].toInt() and 0xFF,
                finalDestination = buffer[5].toInt() and 0xFF,
                payload = buffer.copyOfRange(Zirconia.HEADER_LENGTH, length),
            )
            drop(total)
            return frame
        }
        return null
    }

    private fun drop(count: Int) {
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    private companion object {
        const val MAX_BACKTRACK_RECORDS = 16
    }
}

/** A row of the unit's zone table, reduced to what the companion app shows. */
data class ZoneSummary(val zoneId: Int, val name: String, val hasDevices: Boolean = true) {
    /** Unused slots come back with no name and no fans assigned. */
    val isInUse: Boolean get() = name.isNotBlank() || hasDevices
}

object ZoneViewRow {
    private const val NAME_OFFSET = 1
    private const val NAME_LENGTH = 16
    private const val FLAGS_OFFSET = 23
    private const val FLAG_HAS_DEVICES = 1 shl 5

    /** Parses the leading fields of a `ZoneViewRowV5`; returns null for an empty slot. */
    fun parse(payload: ByteArray): ZoneSummary? {
        if (payload.isEmpty()) return null
        val zoneId = payload[0].toInt() and 0xFF
        if (zoneId == 0) return null
        val end = minOf(payload.size, NAME_OFFSET + NAME_LENGTH)
        var nameEnd = NAME_OFFSET
        while (nameEnd < end && payload[nameEnd] != 0.toByte()) nameEnd++
        val name = if (nameEnd > NAME_OFFSET) {
            String(payload, NAME_OFFSET, nameEnd - NAME_OFFSET, Charsets.UTF_8).trim()
        } else {
            ""
        }
        val hasDevices = payload.size <= FLAGS_OFFSET ||
            (payload[FLAGS_OFFSET].toInt() and FLAG_HAS_DEVICES) != 0
        return ZoneSummary(zoneId, name, hasDevices)
    }
}
