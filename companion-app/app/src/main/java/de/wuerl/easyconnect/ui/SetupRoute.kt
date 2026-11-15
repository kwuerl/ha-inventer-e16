// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wuerl.easyconnect.ui.UnitUi.Paired

/** Runtime checks the pairing flow needs before it can scan. */
object PairingPermissions {
    /** Android 12+: "Nearby devices"; Android 8–11: location (BLE scans need it there). */
    fun required(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            // Android 17 groups local-network access with "Nearby devices"; ask for both at once.
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

    fun granted(context: Context) = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothOn(context: Context) =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Only Android 11 and older need Location switched on to deliver BLE scan results. */
    fun locationOk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(LocationManager::class.java) ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return manager.isLocationEnabled
        return manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
}

@Composable
fun SetupRoute(viewModel: SetupViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    fun recheckPairing() = viewModel.onPairingPrerequisites(
        PairingPermissions.granted(context), PairingPermissions.bluetoothOn(context), PairingPermissions.locationOk(context),
    )

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) recheckPairing() else viewModel.onPermissionDenied()
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheckPairing() }

    // Coming back from the system settings: pick up a granted permission or enabled radio.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        when (state.pairing) {
            PairingUi.PermissionDenied, PairingUi.BluetoothOff, PairingUi.LocationOff -> recheckPairing()
            else -> Unit
        }
    }
    LaunchedEffect(state.pairing is PairingUi.NeedsPermission) {
        if (state.pairing is PairingUi.NeedsPermission) permissionLauncher.launch(PairingPermissions.required())
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(
            when (message) {
                is UiText.Raw -> message.text
                is UiText.Res -> resources.getString(message.id, *message.args.toTypedArray())
            },
        )
        viewModel.consumeMessage()
    }

    fun open(url: String?) {
        if (url != null) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    val login = state.haLogin
    if (login != null) {
        HaLoginScreen(
            login = login,
            onNavigation = viewModel::onLoginNavigation,
            onPageError = viewModel::onLoginPageError,
            onClose = viewModel::cancelLogin,
        )
        return
    }

    SetupScreen(
        state = state,
        snackbar = snackbar,
        actions = SetupActions(
            onPair = {
                viewModel.openPairing(
                    PairingPermissions.granted(context), PairingPermissions.bluetoothOn(context), PairingPermissions.locationOk(context),
                )
            },
            onSearchAgain = viewModel::locateUnit,
            onEnterAddress = viewModel::openManualHost,
            onShowKey = { viewModel.toggleShowKey(true) },
            onForget = { viewModel.askForget(true) },
            onConnectHa = viewModel::openHaPicker,
            onCheckIntegration = viewModel::refreshHaStatus,
            onGetIntegration = { open(IntegrationTarget.E16.projectUrl) },
            onOpenHa = { open(viewModel.haUrl()) },
            onSignOut = viewModel::signOut,
            onOpenIntegration = { open(viewModel.integrationUrl()) },
            onRunAgain = viewModel::runIntegrationAgain,
        ),
    )

    state.pairing?.let { pairing ->
        PairingSheet(
            pairing = pairing,
            actions = PairingActions(
                onAllow = { permissionLauncher.launch(PairingPermissions.required()) },
                onOpenSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }
                },
                onEnableBluetooth = { runCatching { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } },
                onEnableLocation = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } },
                onSelect = viewModel::selectUnit,
                onRetry = { recheckPairing() },
                onClose = viewModel::closePairing,
            ),
        )
    }
    state.haPicker?.let { picker ->
        HaPickerSheet(
            picker = picker,
            actions = HaPickerActions(
                onInput = viewModel::onHaInputChange,
                onPick = { viewModel.connectHa(it.url) },
                onContinue = { viewModel.connectHa() },
                onClose = viewModel::closeHaPicker,
            ),
        )
    }
    state.manualHost?.let { manual ->
        ManualHostDialog(
            state = manual,
            onInput = viewModel::onManualHostChange,
            onCheck = viewModel::checkManualHost,
            onUseAnyway = viewModel::useManualHostAnyway,
            onDismiss = viewModel::dismissManualHost,
        )
    }
    val paired = state.unit as? Paired
    if (state.showKey && paired != null) KeyDialog(paired.unit) { viewModel.toggleShowKey(false) }
    if (state.confirmForget) ForgetDialog(onConfirm = viewModel::forgetUnit, onDismiss = { viewModel.askForget(false) })
}
