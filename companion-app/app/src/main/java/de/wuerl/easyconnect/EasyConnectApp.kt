// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect

import android.app.Application
import android.content.Context
import de.wuerl.easyconnect.ble.EasyConnectBle
import de.wuerl.easyconnect.data.KeystoreBlobStore
import de.wuerl.easyconnect.data.SetupStore
import de.wuerl.easyconnect.device.AndroidLan
import de.wuerl.easyconnect.device.LocalNetworkAccess
import de.wuerl.easyconnect.device.UnitLocator
import de.wuerl.easyconnect.ha.HaAuthClient
import de.wuerl.easyconnect.ha.HaDiscovery
import de.wuerl.easyconnect.ha.defaultHaHttpClient

/** Hand-wired dependencies; small enough that a DI framework would be overkill. */
class AppGraph(context: Context) {
    val store = SetupStore(KeystoreBlobStore(context))
    val ble = EasyConnectBle(context)
    val lan = AndroidLan(context)
    val localNetwork = LocalNetworkAccess(context)
    val locator = UnitLocator(lan)
    val http = defaultHaHttpClient()
    val haAuth = HaAuthClient(http, BuildConfig.HA_CLIENT_ID, BuildConfig.HA_REDIRECT_URI)
    val haDiscovery = HaDiscovery(context)
}

class EasyConnectApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
