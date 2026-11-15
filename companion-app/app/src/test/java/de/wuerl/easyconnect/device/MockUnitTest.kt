// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * End-to-end checks of the TLS-PSK client, zone parsing, MDSDP and the subnet sweep against
 * tools/mock_e16.py (a real OpenSSL TLS-PSK server). Skipped when Python 3.13+ is missing,
 * unless REQUIRE_MOCK_E16 is set (CI).
 */
class MockUnitTest {

    private val key = AccessKeyBytes.REFERENCE

    @Test
    fun `verifies the key and reads the zone names`() = runBlocking {
        val result = UnitLocator(LoopbackLan()).probe("127.0.0.1", tlsPort, key)
        result as ProbeResult.Verified
        assertEquals(listOf("Zone 1", "Bedroom", "Hallway"), result.endpoint.zones.map { it.name })
    }

    @Test
    fun `a wrong key is reported as rejected`() = runBlocking {
        val wrong = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val result = UnitLocator(LoopbackLan()).probe("127.0.0.1", tlsPort, wrong)
        assertTrue("expected KeyRejected, got $result", result is ProbeResult.KeyRejected)
    }

    @Test
    fun `a closed port is reported as unreachable`() = runBlocking {
        val result = UnitLocator(LoopbackLan()).probe("127.0.0.1", freePort(), key)
        assertTrue("expected Unreachable, got $result", result is ProbeResult.Unreachable)
    }

    @Test
    fun `finds the unit through MDSDP`() = runBlocking {
        val announcements = Mdsdp.search(listOf(InetAddress.getByName("127.0.0.1")), port = mdsdpPort, timeoutMs = 900)
        assertEquals(1, announcements.size)
        assertEquals("Easy Connect e16", announcements.single().displayName)
        assertEquals("ABCDE-FGHIJ", announcements.single().deviceIdentity)

        val outcome = UnitLocator(LoopbackLan(), tcpPort = tlsPort, mdsdpPort = mdsdpPort).locate(key)
        outcome as LocateOutcome.Found
        assertEquals("127.0.0.1", outcome.endpoint.host)
        assertEquals("Easy Connect e16", outcome.endpoint.displayName)
    }

    @Test
    fun `finds the unit by sweeping the subnet when MDSDP is silent`() = runBlocking {
        val stages = mutableListOf<LocateStage>()
        val outcome = UnitLocator(LoopbackLan(), tcpPort = tlsPort, mdsdpPort = freePort()).locate(key) { stages += it }
        outcome as LocateOutcome.Found
        assertEquals("127.0.0.1", outcome.endpoint.host)
        assertTrue(stages.any { it is LocateStage.Scanning })
    }

    /** Pretends the phone is 127.0.0.2/24 so the sweep covers the mock on 127.0.0.1. */
    private class LoopbackLan : LanEnvironment {
        override fun localIpv4() = LocalIpv4(InetAddress.getByName("127.0.0.2") as Inet4Address, 24)
        override fun discoveryTargets(local: LocalIpv4) = listOf(InetAddress.getByName("127.0.0.1"))
    }

    object AccessKeyBytes {
        val REFERENCE = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    }

    companion object {
        private var process: Process? = null
        private var tlsPort = 0
        private var mdsdpPort = 0

        private fun freePort() = ServerSocket(0).use { it.localPort }

        @BeforeClass
        @JvmStatic
        fun startMock() {
            val script = File(System.getProperty("user.dir") ?: ".").resolve("../tools/mock_e16.py").canonicalFile
            val python = listOf("python3", "python3.14", "python3.13").firstOrNull { cmd ->
                runCatching {
                    ProcessBuilder(cmd, "-c", "import sys; sys.exit(0 if sys.version_info >= (3, 13) else 1)")
                        .start().waitFor() == 0
                }.getOrDefault(false)
            }
            val available = python != null && script.exists()
            check(available || System.getenv("REQUIRE_MOCK_E16") == null) { "REQUIRE_MOCK_E16: Python 3.13+ and $script needed" }
            assumeTrue("Python 3.13+ needed for the TLS-PSK mock", available)
            tlsPort = freePort()
            mdsdpPort = freePort()
            process = ProcessBuilder(
                python, script.path, "--host", "127.0.0.1", "--port", "$tlsPort",
                "--mdsdp-port", "$mdsdpPort", "--key", "0102030405060708",
            ).redirectErrorStream(true).redirectOutput(File.createTempFile("mock_e16", ".log")).start()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val up = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", tlsPort), 200) } }.isSuccess
                if (up) return
                Thread.sleep(100)
            }
            error("mock_e16.py did not start")
        }

        @AfterClass
        @JvmStatic
        fun stopMock() {
            process?.destroy()
        }
    }
}
