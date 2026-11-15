// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * MDSDP, a UDP discovery protocol of the Zirconia device family: an 8-byte "Search" broadcast
 * on port 47818, answered by an "AnnounceV4" datagram carrying the unit's IP and TCP port.
 * Derived from how the official app finds devices, not verified on an e16 and not part of
 * docs/protocol.md; [UnitLocator] falls back to a TCP sweep of the subnet.
 */
object Mdsdp {
    const val PORT = 47818
    const val MULTICAST_GROUP = "239.255.93.239"

    private val PROTOCOL_ID = "MDSDP".toByteArray(Charsets.US_ASCII)
    private const val TYPE_MASK = 0x3F
    private const val FLAG_REQUEST = 0x80
    private const val TYPE_ANNOUNCE_V4 = 0x00
    private const val TYPE_SEARCH = 0x03
    private const val MIN_ANNOUNCE_LENGTH = 52
    private const val STRINGS_OFFSET = 48

    data class Announcement(
        val ip: String,
        val port: Int,
        val deviceUuid: String,
        val serviceType: String,
        val deviceIdentity: String,
        val displayName: String,
        val locationName: String,
    )

    fun searchRequest(counter: Int = 0): ByteArray = ByteArray(8).also {
        PROTOCOL_ID.copyInto(it)
        it[5] = (TYPE_SEARCH or FLAG_REQUEST).toByte() // 0x83
        it[6] = (counter and 0xFF).toByte() // uint16 LE
        it[7] = ((counter shr 8) and 0xFF).toByte()
    }

    /** Parses an AnnounceV4 reply; [sourceIp] is used when the unit announces 0.0.0.0. */
    fun parseAnnouncement(data: ByteArray, length: Int = data.size, sourceIp: String? = null): Announcement? {
        if (length < MIN_ANNOUNCE_LENGTH) return null
        for (i in PROTOCOL_ID.indices) if (data[i] != PROTOCOL_ID[i]) return null
        val type = data[5].toInt() and 0xFF
        if (type and FLAG_REQUEST != 0 || type and TYPE_MASK != TYPE_ANNOUNCE_V4) return null

        val announcedIp = (8..11).joinToString(".") { (data[it].toInt() and 0xFF).toString() }
        val ip = if (announcedIp == "0.0.0.0" && sourceIp != null) sourceIp else announcedIp
        val port = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
        val uuidBuffer = ByteBuffer.wrap(data, 16, 16)
        val deviceUuid = UUID(uuidBuffer.long, uuidBuffer.long).toString()

        val strings = mutableListOf<String>()
        var offset = STRINGS_OFFSET
        while (strings.size < 4 && offset < length) {
            val len = data[offset].toInt() and 0xFF
            val start = offset + 1
            if (start + len > length) break
            strings += String(data, start, len, Charsets.UTF_8).trim()
            offset = start + len
        }
        return Announcement(
            ip = ip,
            port = if (port == 0) Zirconia.TCP_PORT else port,
            deviceUuid = deviceUuid,
            serviceType = strings.getOrElse(0) { "" },
            deviceIdentity = strings.getOrElse(1) { "" },
            displayName = strings.getOrElse(2) { "" },
            locationName = strings.getOrElse(3) { "" },
        )
    }

    /**
     * Broadcasts a search to every address in [targets] a few times and collects replies
     * for [timeoutMs]. Blocking; call from an IO dispatcher. [prepare] lets the caller bind
     * the socket to the Wi-Fi network before anything is sent.
     */
    fun search(
        targets: List<InetAddress>,
        port: Int = PORT,
        timeoutMs: Long = 2_500,
        rounds: Int = 3,
        prepare: (DatagramSocket) -> Unit = {},
    ): List<Announcement> {
        val found = LinkedHashMap<String, Announcement>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            prepare(socket)
            socket.bind(null)
            val buffer = ByteArray(1500)
            val start = System.currentTimeMillis()
            val roundLength = timeoutMs / rounds
            for (round in 0 until rounds) {
                val request = searchRequest(round)
                for (target in targets) {
                    runCatching { socket.send(DatagramPacket(request, request.size, target, port)) }
                }
                val roundEnd = start + roundLength * (round + 1)
                while (true) {
                    val remaining = roundEnd - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val announcement = parseAnnouncement(packet.data, packet.length, packet.address?.hostAddress)
                    if (announcement != null) found[announcement.deviceUuid + announcement.ip] = announcement
                }
            }
        }
        return found.values.toList()
    }
}
