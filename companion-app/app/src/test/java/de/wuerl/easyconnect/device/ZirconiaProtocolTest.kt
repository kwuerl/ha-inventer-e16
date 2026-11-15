// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZirconiaProtocolTest {

    private fun hex(s: String) = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `builds a captured UserOverride frame`() {
        // Capture 07-speed4-heat-recovery-60min in tests/test_acknowledgements.py
        val payload = hex("BA 0A 00 08 05 04 01 01 10 0E 00 00")
        val frame = ProtocolPacketV2.build(packetType = 56, operationFlags = 1, payload = payload)
        assertArrayEquals(hex("99 16 00 38 01 00 00 00 00 00 BA 0A 00 08 05 04 01 01 10 0E 00 00"), frame)
    }

    @Test
    fun `builds data requests with header, index and destination`() {
        assertArrayEquals(hex("ff 0a 00 91 02 00 00 00 00 00"), ProtocolPacketV2.dataRequest(145))
        assertArrayEquals(hex("bb 0b 00 92 02 00 00 00 00 00 02"), ProtocolPacketV2.dataRequest(146, byteArrayOf(2)))
        assertArrayEquals(hex("d1 0a 00 0d 02 9f 00 00 00 00"), ProtocolPacketV2.dataRequest(13, finalDestination = 159))
    }

    @Test
    fun `decoder splits frames and skips backtracking records`() {
        val a = ProtocolPacketV2.build(145, 64, byteArrayOf(4, 5, 0))
        // one 3-byte routing record after the frame; the CRC covers the record count in byte 2
        val withBacktrack = a.copyOf().also { it[2] = 1 }
        withBacktrack[0] = crc8Zirconia(withBacktrack, 1, withBacktrack[1].toInt()).toByte()
        val b = ProtocolPacketV2.build(146, 64, byteArrayOf(1, 'X'.code.toByte()))
        val stream = withBacktrack + byteArrayOf(0x00, 0x01, 0xC4.toByte()) + b

        val decoder = FrameDecoder()
        // feed byte by byte to exercise partial frames
        val frames = mutableListOf<Frame>()
        for (byte in stream) {
            decoder.feed(byteArrayOf(byte))
            while (true) frames += decoder.next() ?: break
        }
        assertEquals(listOf(145, 146), frames.map { it.packetType })
        assertArrayEquals(byteArrayOf(4, 5, 0), frames[0].payload)
        assertArrayEquals(byteArrayOf(1, 'X'.code.toByte()), frames[1].payload)
    }

    @Test
    fun `parses zone rows and recognises unused slots`() {
        val row = ByteArray(85).also {
            it[0] = 1
            "Zone 1".toByteArray().copyInto(it, 1)
            it[23] = 0b100000
        }
        assertEquals(ZoneSummary(1, "Zone 1", hasDevices = true), ZoneViewRow.parse(row))
        assertNull(ZoneViewRow.parse(ByteArray(85)))
        val unused = ZoneViewRow.parse(ByteArray(85).also { it[0] = 4 })!!
        assertFalse(unused.isInUse)
        assertTrue(ZoneViewRow.parse(byteArrayOf(2, 'B'.code.toByte(), 'e'.code.toByte(), 0, 0))!!.isInUse)
    }

    @Test
    fun `CRC-8 matches the test vector of the protocol docs`() {
        assertEquals(0xAF, crc8Zirconia("123456789".toByteArray(), from = 0))
    }

    @Test
    fun `key as hex, zeros count as no key`() {
        val key = hex("01 02 03 04 05 06 07 08")
        assertEquals("0102030405060708", AccessKey.hex(key))
        assertTrue(AccessKey.isBlank(ByteArray(8)))
        assertTrue(AccessKey.isBlank(null))
        assertFalse(AccessKey.isBlank(key))
    }
}
