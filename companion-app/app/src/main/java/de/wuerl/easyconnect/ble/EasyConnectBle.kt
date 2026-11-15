// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import de.wuerl.easyconnect.device.AccessKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** An Easy Connect seen while scanning. */
data class FoundUnit(val address: String, val name: String, val rssi: Int)

/** Standard Device Information values (all optional). */
data class DeviceInformation(
    val model: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val software: String? = null,
    val manufacturer: String? = null,
)

/** Everything read from the unit during pairing. */
class UnitCredentials(
    val address: String,
    val bleName: String?,
    val deviceId: String,
    val key: ByteArray,
    val tlsIdentity: ByteArray?,
    val info: DeviceInformation,
)

sealed interface PairingProgress {
    data object Connecting : PairingProgress
    data object Reconnecting : PairingProgress

    /** Connected; the key still reads zeros because pairing mode isn't open (yet). */
    data class WaitingForPairingMode(val info: DeviceInformation) : PairingProgress
    data object ReadingKey : PairingProgress
}

class PairingModeTimeoutException : BleException("the unit did not enter pairing mode")
class BluetoothUnavailableException : BleException("Bluetooth is not available")
class ScanFailedException(val code: Int) : BleException("Bluetooth scan failed ($code)")

/**
 * Pairing with the Easy Connect e16 over BLE: scan, connect, wait for the pairing window and
 * read device ID + PSK. Nothing is ever written to the unit.
 */
@SuppressLint("MissingPermission")
class EasyConnectBle(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Emits the matching units seen so far, strongest signal first. */
    fun scan(): Flow<List<FoundUnit>> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: throw BluetoothUnavailableException()
        val found = LinkedHashMap<String, FoundUnit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }

            private fun handle(result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val services = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
                if (!EasyConnectGatt.looksLikeEasyConnect(name, services)) return
                val address = result.device.address
                found[address] = FoundUnit(address, name?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME, result.rssi)
                trySend(found.values.sortedByDescending { it.rssi })
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * Connects to [unit] and polls until pairing mode opens, then reads device ID and key.
     * The unit may drop the link when it enters pairing mode, so the link is re-established
     * until [timeoutMs] runs out.
     */
    suspend fun readCredentials(
        unit: FoundUnit,
        timeoutMs: Long = 150_000,
        onProgress: (PairingProgress) -> Unit = {},
    ): UnitCredentials {
        val bluetooth = adapter ?: throw BluetoothUnavailableException()
        val device = bluetooth.getRemoteDevice(unit.address)
        val deadline = System.currentTimeMillis() + timeoutMs
        var info: DeviceInformation? = null
        var firstAttempt = true
        while (true) {
            onProgress(if (firstAttempt) PairingProgress.Connecting else PairingProgress.Reconnecting)
            firstAttempt = false
            val link = BleLink(appContext, device)
            try {
                link.connect()
                val deviceInfo = info ?: readDeviceInformation(link).also { info = it }
                onProgress(PairingProgress.WaitingForPairingMode(deviceInfo))
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val key = link.read(EasyConnectGatt.TLS_KEY)
                        if (!AccessKey.isBlank(key)) {
                            onProgress(PairingProgress.ReadingKey)
                            val deviceId = EasyConnectGatt.parseDeviceId(link.read(EasyConnectGatt.DEVICE_ID))
                            if (deviceId.isNotEmpty()) {
                                val identity = runCatching { link.read(EasyConnectGatt.TLS_IDENTITY) }.getOrNull()
                                return UnitCredentials(unit.address, unit.name, deviceId, key, identity, deviceInfo)
                            }
                        }
                    } catch (e: BleException) {
                        // A refused read on a live link (outside pairing mode) is just "not yet";
                        // a dropped link goes to the reconnect handling below.
                        if (e is BleDisconnectedException || !link.isConnected) throw e
                    }
                    delay(POLL_INTERVAL_MS)
                }
                throw PairingModeTimeoutException()
            } catch (e: BleException) {
                if (e is PairingModeTimeoutException || System.currentTimeMillis() >= deadline) throw e
                delay(RECONNECT_DELAY_MS)
            } finally {
                link.close()
            }
        }
    }

    private suspend fun readDeviceInformation(link: BleLink) = DeviceInformation(
        model = link.readText(EasyConnectGatt.MODEL),
        serial = link.readText(EasyConnectGatt.SERIAL),
        firmware = link.readText(EasyConnectGatt.FIRMWARE),
        hardware = link.readText(EasyConnectGatt.HARDWARE),
        software = link.readText(EasyConnectGatt.SOFTWARE),
        manufacturer = link.readText(EasyConnectGatt.MANUFACTURER),
    )

    companion object {
        const val DEFAULT_NAME = "Easy Connect e16"
        private const val POLL_INTERVAL_MS = 1_200L
        private const val RECONNECT_DELAY_MS = 1_500L
    }
}
