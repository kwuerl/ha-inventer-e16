// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.DatagramSocket
import java.net.Inet4Address
import javax.net.SocketFactory

/**
 * [LanEnvironment] backed by the phone's Wi-Fi (or Ethernet) network. Sockets are bound to
 * that network explicitly so discovery still works when Android routes "default" traffic over
 * mobile data (for example on a Wi-Fi without internet access).
 */
class AndroidLan(context: Context) : LanEnvironment {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    private fun localNetwork(): Pair<Network, LocalIpv4>? {
        @Suppress("DEPRECATION") // allNetworks is the simplest way to find a non-default Wi-Fi network
        val networks = connectivity.allNetworks
        val candidates = networks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) return@mapNotNull null
            val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val v4 = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return@mapNotNull null
            network to LocalIpv4(v4.address as Inet4Address, v4.prefixLength)
        }
        return candidates.firstOrNull { it.first == connectivity.activeNetwork } ?: candidates.firstOrNull()
    }

    override fun localIpv4(): LocalIpv4? = localNetwork()?.second

    override val socketFactory: SocketFactory
        get() = localNetwork()?.first?.socketFactory ?: SocketFactory.getDefault()

    override fun bindDatagram(socket: DatagramSocket) {
        localNetwork()?.first?.let { runCatching { it.bindSocket(socket) } }
    }

    override fun <T> withMulticast(block: () -> T): T {
        val lock = wifi?.createMulticastLock("easyconnect-discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        return try {
            block()
        } finally {
            runCatching { lock?.release() }
        }
    }

    /** True when the phone is on a Wi-Fi/Ethernet network with an IPv4 address. */
    fun isOnLocalNetwork(): Boolean = localNetwork() != null
}
