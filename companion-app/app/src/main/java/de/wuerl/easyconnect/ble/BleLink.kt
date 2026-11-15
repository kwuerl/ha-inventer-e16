// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.UUID

open class BleException(message: String) : Exception(message)
class BleDisconnectedException(val status: Int) : BleException("Bluetooth connection lost (status $status)")
class BleTimeoutException(what: String) : BleException("Bluetooth $what timed out")

/**
 * One GATT connection with suspend functions for the few operations the app needs. Android's
 * GATT stack allows a single outstanding operation, so every call is serialised.
 * Permissions (BLUETOOTH_CONNECT) are checked by the caller before a link is created.
 */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context, private val device: BluetoothDevice) : Closeable {

    private val opLock = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connected = CompletableDeferred<Unit>()
    @Volatile private var servicesDiscovered = CompletableDeferred<Unit>()
    @Volatile private var pendingRead: CompletableDeferred<ByteArray>? = null
    @Volatile private var pendingReadUuid: UUID? = null
    @Volatile private var linkUp = false

    val isConnected: Boolean get() = linkUp && gatt != null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                linkUp = true
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                linkUp = false
                val error = BleDisconnectedException(status)
                connected.completeExceptionally(error)
                servicesDiscovered.completeExceptionally(error)
                pendingRead?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesDiscovered.complete(Unit)
            } else {
                servicesDiscovered.completeExceptionally(BleException("service discovery failed (status $status)"))
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = completeRead(characteristic.uuid, value, status)

        @Deprecated("Called on Android 12L and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                completeRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)
            }
        }
    }

    private fun completeRead(uuid: UUID, value: ByteArray, status: Int) {
        val pending = pendingRead ?: return
        if (uuid != pendingReadUuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            pending.complete(value.copyOf())
        } else {
            pending.completeExceptionally(BleException("read of $uuid failed (status $status)"))
        }
    }

    /** Connects and discovers services; retries the infamous status-133 failures a few times. */
    suspend fun connect(timeoutMs: Long = 15_000) {
        var lastError: Exception? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            connected = CompletableDeferred()
            servicesDiscovered = CompletableDeferred()
            gatt?.close()
            @Suppress("DEPRECATION") // the replacement needs API 37; this one works on all versions
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeout(timeoutMs) { connected.await() }
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                discoverServices()
                return
            } catch (e: TimeoutCancellationException) {
                lastError = BleTimeoutException("connection")
            } catch (e: BleException) {
                lastError = e
            }
            gatt?.close()
            gatt = null
            if (attempt < CONNECT_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        throw lastError ?: BleException("could not connect")
    }

    private suspend fun discoverServices() {
        val g = gatt ?: throw BleDisconnectedException(-1)
        if (!g.discoverServices()) throw BleException("service discovery could not start")
        try {
            withTimeout(DISCOVERY_TIMEOUT_MS) { servicesDiscovered.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleTimeoutException("service discovery")
        }
        if (g.getService(EasyConnectGatt.AUTH_SERVICE) == null && refreshCache(g)) {
            // Android caches GATT tables per address; a stale cache hides the auth service.
            servicesDiscovered = CompletableDeferred()
            delay(300)
            if (g.discoverServices()) {
                runCatching { withTimeout(DISCOVERY_TIMEOUT_MS) { servicesDiscovered.await() } }
            }
        }
    }

    /** Calls the hidden BluetoothGatt.refresh(), the standard workaround for stale caches. */
    private fun refreshCache(g: BluetoothGatt): Boolean = runCatching {
        g.javaClass.getMethod("refresh").invoke(g) as Boolean
    }.getOrDefault(false)

    fun hasCharacteristic(uuid: UUID): Boolean = findCharacteristic(uuid) != null

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.asSequence()?.flatMap { it.characteristics.asSequence() }?.firstOrNull { it.uuid == uuid }

    /** Reads a characteristic by UUID, wherever it lives. */
    suspend fun read(uuid: UUID, timeoutMs: Long = 6_000): ByteArray = opLock.withLock {
        val g = gatt ?: throw BleDisconnectedException(-1)
        val characteristic = findCharacteristic(uuid) ?: throw BleException("characteristic $uuid not found")
        val deferred = CompletableDeferred<ByteArray>()
        pendingReadUuid = uuid
        pendingRead = deferred
        try {
            if (!g.readCharacteristic(characteristic)) throw BleException("read of $uuid could not start")
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleTimeoutException("read")
        } finally {
            pendingRead = null
            pendingReadUuid = null
        }
    }

    suspend fun readText(uuid: UUID): String? = runCatching { read(uuid) }.getOrNull()
        ?.let { raw -> String(raw, Charsets.UTF_8).trimEnd('\u0000').trim() }
        ?.takeIf { it.isNotEmpty() }

    override fun close() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 700L
        const val DISCOVERY_TIMEOUT_MS = 10_000L
    }
}
