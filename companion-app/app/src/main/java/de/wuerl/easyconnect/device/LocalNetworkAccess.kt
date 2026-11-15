// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17 (API 37) blocks all local-network traffic of apps that target it until the user
 * grants ACCESS_LOCAL_NETWORK ("Nearby devices"): sockets, OkHttp, mDNS, the login WebView. The
 * system doesn't report it either: TCP connections time out and UDP fails with EPERM. So the app
 * asks for it before it touches the network, see [de.wuerl.easyconnect.ui.LocalNetworkGate].
 */
class LocalNetworkAccess(private val context: Context) {

    fun isGranted(): Boolean =
        !isRequired() || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** `Manifest.permission.ACCESS_LOCAL_NETWORK`, spelled out because it is new in API 37. */
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        fun isRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
    }
}
