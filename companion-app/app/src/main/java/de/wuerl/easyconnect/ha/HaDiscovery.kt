// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ha

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/** A Home Assistant instance announced via mDNS (`_home-assistant._tcp`). */
data class DiscoveredHa(val name: String, val url: String, val version: String?)

/** Finds Home Assistant on the local network the same way the official companion app does. */
class HaDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    private sealed interface Event {
        data class Found(val info: NsdServiceInfo) : Event
        data class Lost(val name: String) : Event
    }

    fun discover(): Flow<List<DiscoveredHa>> = callbackFlow {
        // NSD calls back on its own thread; funnel everything through one coroutine.
        val events = Channel<Event>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                events.trySend(Event.Found(info))
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                events.trySend(Event.Lost(info.serviceName))
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                events.close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        trySend(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        // Resolve one service at a time: older Android versions reject parallel resolves.
        val worker = launch {
            val found = LinkedHashMap<String, DiscoveredHa>()
            for (event in events) {
                when (event) {
                    is Event.Found -> {
                        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(event.info) } ?: continue
                        found[resolved.serviceName] = toDiscovered(resolved) ?: continue
                    }
                    is Event.Lost -> if (found.remove(event.name) == null) continue
                }
                send(found.values.toList())
            }
        }
        awaitClose {
            worker.cancel()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION") // resolveService still works everywhere and keeps this simple
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (cont.isActive) cont.resume(serviceInfo)
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun toDiscovered(info: NsdServiceInfo): DiscoveredHa? {
        val attributes = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
        val address: InetAddress? = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
        } else {
            info.host
        }
        val advertised = listOf("internal_url", "base_url").mapNotNull { attributes[it]?.takeIf(String::isNotBlank) }.firstOrNull()
        // Prefer the resolved IP for plain HTTP (".local" names don't resolve everywhere);
        // keep the advertised host name for HTTPS so the certificate still matches.
        val url = when {
            advertised != null && advertised.startsWith("https://") -> advertised
            address != null -> "http://${address.hostAddress?.let { if (address is Inet4Address) it else "[$it]" }}:${info.port}"
            advertised != null -> advertised
            else -> return null
        }
        val name = attributes["location_name"]?.takeIf(String::isNotBlank) ?: info.serviceName
        return DiscoveredHa(name = name, url = url.trimEnd('/'), version = attributes["version"]?.takeIf(String::isNotBlank))
    }

    private companion object {
        const val SERVICE_TYPE = "_home-assistant._tcp."
        const val RESOLVE_TIMEOUT_MS = 6_000L
    }
}
