// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.SocketFactory

/** The phone's IPv4 address on the local network. */
data class LocalIpv4(val address: Inet4Address, val prefixLength: Int) {
    private val addressInt: Int get() = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    private val mask: Int get() = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)

    val broadcast: Inet4Address get() = intToAddress(addressInt or mask.inv())

    /**
     * Hosts worth probing: the whole subnet for /24 or smaller networks, otherwise the /24
     * around the phone's own address (a full /16 scan would take minutes).
     */
    fun scanTargets(): List<Inet4Address> {
        val effectivePrefix = prefixLength.coerceAtLeast(24)
        val effectiveMask = -1 shl (32 - effectivePrefix)
        val network = addressInt and effectiveMask
        val hostCount = (1 shl (32 - effectivePrefix)) - 2
        val own = addressInt
        return (1..hostCount).map { network + it }.filter { it != own }.map(::intToAddress)
    }

    private fun intToAddress(value: Int): Inet4Address = InetAddress.getByAddress(
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
    ) as Inet4Address
}

/** Platform hooks the locator needs; implemented on Android by [AndroidLan]. */
interface LanEnvironment {
    fun localIpv4(): LocalIpv4?

    /** Where MDSDP searches are sent: limited + subnet broadcast and the MDSDP multicast group. */
    fun discoveryTargets(local: LocalIpv4): List<InetAddress> = listOf(
        InetAddress.getByName("255.255.255.255"),
        local.broadcast,
        InetAddress.getByName(Mdsdp.MULTICAST_GROUP),
    ).distinct()

    val socketFactory: SocketFactory get() = SocketFactory.getDefault()
    fun bindDatagram(socket: DatagramSocket) {}
    fun <T> withMulticast(block: () -> T): T = block()
}

/** A verified TLS-PSK endpoint of the unit. */
data class UnitEndpoint(
    val host: String,
    val port: Int,
    val zones: List<ZoneSummary>,
    val displayName: String? = null,
)

sealed interface ProbeResult {
    val host: String
    val port: Int

    data class Verified(val endpoint: UnitEndpoint) : ProbeResult {
        override val host get() = endpoint.host
        override val port get() = endpoint.port
    }

    data class KeyRejected(override val host: String, override val port: Int, val detail: String) : ProbeResult
    data class Unreachable(override val host: String, override val port: Int, val detail: String) : ProbeResult
    data class Failed(override val host: String, override val port: Int, val detail: String) : ProbeResult
}

sealed interface LocateStage {
    data object Discovering : LocateStage
    data class Scanning(val subnet: String) : LocateStage
    data class Checking(val host: String) : LocateStage
}

sealed interface LocateOutcome {
    data class Found(val endpoint: UnitEndpoint) : LocateOutcome
    data object NoNetwork : LocateOutcome

    /** Nothing accepted the key. [rejectedBy] lists units that answered but refused it. */
    data class NotFound(val rejectedBy: List<String>) : LocateOutcome
}

/** Finds the paired unit on the LAN: MDSDP first, then a TCP sweep of the subnet. */
class UnitLocator(
    private val lan: LanEnvironment,
    private val tcpPort: Int = Zirconia.TCP_PORT,
    private val mdsdpPort: Int = Mdsdp.PORT,
) {

    suspend fun probe(host: String, port: Int, key: ByteArray, displayName: String? = null): ProbeResult =
        withContext(Dispatchers.IO) {
            try {
                ZirconiaTlsSession.open(host, port, key, lan.socketFactory).use { session ->
                    val zones = try {
                        session.readZones()
                    } catch (e: IOException) {
                        // The handshake alone proves the key; the zone list is a bonus.
                        emptyList()
                    }
                    ProbeResult.Verified(UnitEndpoint(host, port, zones, displayName))
                }
            } catch (e: UnitConnectException.KeyRejected) {
                ProbeResult.KeyRejected(host, port, e.message.orEmpty())
            } catch (e: UnitConnectException.Unreachable) {
                ProbeResult.Unreachable(host, port, e.message.orEmpty())
            } catch (e: UnitConnectException) {
                ProbeResult.Failed(host, port, e.message.orEmpty())
            }
        }

    suspend fun locate(key: ByteArray, onStage: (LocateStage) -> Unit = {}): LocateOutcome = withContext(Dispatchers.IO) {
        val local = lan.localIpv4() ?: return@withContext LocateOutcome.NoNetwork
        val rejected = linkedSetOf<String>()
        val tried = mutableSetOf<String>()

        onStage(LocateStage.Discovering)
        val announcements = runCatching {
            val targets = lan.discoveryTargets(local)
            lan.withMulticast { Mdsdp.search(targets, port = mdsdpPort, prepare = lan::bindDatagram) }
        }.getOrDefault(emptyList())
        for (a in announcements) {
            onStage(LocateStage.Checking(a.ip))
            tried += a.ip
            when (val result = probe(a.ip, a.port, key, a.displayName.ifBlank { null })) {
                is ProbeResult.Verified -> return@withContext LocateOutcome.Found(result.endpoint)
                is ProbeResult.KeyRejected -> rejected += a.ip
                else -> Unit
            }
        }

        onStage(LocateStage.Scanning("${local.address.hostAddress}/${local.prefixLength.coerceAtLeast(24)}"))
        val open = sweep(local.scanTargets().filter { (it.hostAddress ?: "") !in tried }, tcpPort)
        // Hosts that also expose the MQTT port look most like an e16; check those first.
        val ranked = open.map { it to isOpen(it, Zirconia.MQTT_PORT) }.sortedByDescending { it.second }.map { it.first }
        for (address in ranked) {
            val host = address.hostAddress ?: continue
            onStage(LocateStage.Checking(host))
            when (val result = probe(host, tcpPort, key)) {
                is ProbeResult.Verified -> return@withContext LocateOutcome.Found(result.endpoint)
                is ProbeResult.KeyRejected -> rejected += host
                else -> Unit
            }
        }
        LocateOutcome.NotFound(rejected.toList())
    }

    private suspend fun sweep(targets: List<Inet4Address>, port: Int): List<Inet4Address> = coroutineScope {
        val gate = Semaphore(SWEEP_PARALLELISM)
        targets.map { address ->
            async(Dispatchers.IO) { gate.withPermit { address.takeIf { isOpen(it, port) } } }
        }.awaitAll().filterNotNull()
    }

    private fun isOpen(address: InetAddress, port: Int): Boolean = try {
        lan.socketFactory.createSocket().use { it.connect(InetSocketAddress(address, port), SWEEP_TIMEOUT_MS); true }
    } catch (_: IOException) {
        false
    }

    private companion object {
        const val SWEEP_PARALLELISM = 48
        const val SWEEP_TIMEOUT_MS = 400
    }
}
