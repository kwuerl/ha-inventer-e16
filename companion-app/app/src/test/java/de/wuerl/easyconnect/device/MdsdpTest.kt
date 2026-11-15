// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID

class MdsdpTest {

    @Test
    fun `search request is MDSDP, Search or Request flag, little-endian counter`() {
        assertArrayEquals(
            byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), 'P'.code.toByte(), 0x83.toByte(), 0x02, 0x01),
            Mdsdp.searchRequest(0x0102),
        )
    }

    private fun announce(ip: ByteArray, port: Int, vararg strings: String): ByteArray {
        val uuid = UUID.fromString("00000000-0000-4000-8000-000000000e16")
        val buf = ByteBuffer.allocate(256)
        buf.put("MDSDP".toByteArray()).put(0).putShort(7).put(ip).putShort(port.toShort()).putShort(120)
        buf.putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
        buf.put(ByteArray(16))
        strings.forEach { buf.put(it.length.toByte()).put(it.toByteArray()) }
        return buf.array().copyOf(buf.position())
    }

    @Test
    fun `parses AnnounceV4`() {
        val data = announce(byteArrayOf(192.toByte(), 0, 2, 10), 47820, "zirconia", "ABCDE-FGHIJ", "Easy Connect e16", "Home")
        val a = Mdsdp.parseAnnouncement(data)!!
        assertEquals("192.0.2.10", a.ip)
        assertEquals(47820, a.port)
        assertEquals("00000000-0000-4000-8000-000000000e16", a.deviceUuid)
        assertEquals("ABCDE-FGHIJ", a.deviceIdentity)
        assertEquals("Easy Connect e16", a.displayName)
        assertEquals("Home", a.locationName)
    }

    @Test
    fun `falls back to the sender address and default port, ignores requests`() {
        val data = announce(byteArrayOf(0, 0, 0, 0), 0, "", "", "", "")
        val a = Mdsdp.parseAnnouncement(data, sourceIp = "10.0.0.7")!!
        assertEquals("10.0.0.7", a.ip)
        assertEquals(Zirconia.TCP_PORT, a.port)
        assertNull(Mdsdp.parseAnnouncement(Mdsdp.searchRequest()))
        assertNull(Mdsdp.parseAnnouncement(data.copyOf().also { it[5] = 0x83.toByte() }))
    }

    @Test
    fun `subnet maths`() {
        val local = LocalIpv4(InetAddress.getByName("192.0.2.20") as Inet4Address, 24)
        assertEquals("192.0.2.255", local.broadcast.hostAddress)
        val targets = local.scanTargets().map { it.hostAddress }
        assertEquals(253, targets.size)
        assertEquals("192.0.2.1", targets.first())
        assertEquals("192.0.2.254", targets.last())
        val wide = LocalIpv4(InetAddress.getByName("10.1.2.3") as Inet4Address, 16)
        assertEquals("10.1.255.255", wide.broadcast.hostAddress)
        assertEquals("10.1.2.1", wide.scanTargets().first().hostAddress) // limited to the /24
    }
}
