// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsFatalAlertReceived
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.SocketFactory

/** Why a TLS-PSK session to the unit could not be opened. */
sealed class UnitConnectException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** Nothing answered on the port (refused, timed out, no route). */
    class Unreachable(message: String, cause: Throwable? = null) : UnitConnectException(message, cause)

    /** The unit answered but refused the handshake: the key (or identity) is wrong. */
    class KeyRejected(message: String, cause: Throwable? = null) : UnitConnectException(message, cause)

    /** Something else went wrong during the handshake. */
    class Handshake(message: String, cause: Throwable? = null) : UnitConnectException(message, cause)
}

/**
 * A TLS 1.2 PSK session to an Easy Connect e16 (docs/protocol.md, Transport): identity `"zirconia"`, the raw
 * BLE key as PSK, cipher TLS_PSK_WITH_AES_128_CBC_SHA (0x008C). BouncyCastle provides the TLS
 * stack because the platform TLS provider does not reliably offer PSK suites.
 *
 * Requests are strictly sequential (the unit serves one outstanding request at a time).
 */
class ZirconiaTlsSession private constructor(
    private val socket: Socket,
    private val protocol: TlsClientProtocol,
) : Closeable {

    private val decoder = FrameDecoder()
    private val readBuffer = ByteArray(1024)

    /** Sends a DataRequest and returns the payload of the first reply of the same type. */
    @Throws(IOException::class)
    fun request(
        packetType: Int,
        payload: ByteArray = ByteArray(0),
        finalDestination: Int = Zirconia.Address.MASTER,
        timeoutMs: Long = 5_000,
    ): ByteArray {
        val output = protocol.outputStream
        output.write(ProtocolPacketV2.dataRequest(packetType, payload, finalDestination))
        output.flush()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            while (true) {
                val frame = decoder.next() ?: break
                if (frame.packetType == packetType) return frame.payload
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw SocketTimeoutException("no reply to packet type $packetType")
            socket.soTimeout = remaining.toInt().coerceAtLeast(1)
            val read = protocol.inputStream.read(readBuffer)
            if (read < 0) throw EOFException("connection closed by the unit")
            decoder.feed(readBuffer, read)
        }
    }

    /** Reads the zone table (`ZONE_VIEW_HEADER` + one `ZONE_VIEW_ROW` per slot). */
    @Throws(IOException::class)
    fun readZones(): List<ZoneSummary> {
        val header = request(Zirconia.PacketType.ZONE_VIEW_HEADER)
        val rows = if (header.isNotEmpty()) header[0].toInt() and 0xFF else 0
        return (0 until rows.coerceAtMost(MAX_ZONES)).mapNotNull { index ->
            ZoneViewRow.parse(request(Zirconia.PacketType.ZONE_VIEW_ROW, byteArrayOf(index.toByte())))
        }.filter { it.isInUse }
    }

    override fun close() {
        runCatching { protocol.close() }
        runCatching { socket.close() }
    }

    private class PskClient(identity: ByteArray, key: ByteArray) :
        PSKTlsClient(BcTlsCrypto(SecureRandom()), BasicTlsPSKIdentity(identity, key)) {
        var receivedAlert: Short? = null

        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA)

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()

        override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
            receivedAlert = alertDescription
        }
    }

    companion object {
        private const val MAX_ZONES = 16

        /** Alerts that mean "your key/identity is not mine". */
        private val KEY_ALERTS = setOf(
            AlertDescription.bad_record_mac,
            AlertDescription.decryption_failed,
            AlertDescription.decrypt_error,
            AlertDescription.handshake_failure,
            AlertDescription.unknown_psk_identity,
            AlertDescription.access_denied,
            AlertDescription.illegal_parameter,
        )

        @Throws(UnitConnectException::class)
        fun open(
            host: String,
            port: Int,
            key: ByteArray,
            socketFactory: SocketFactory = SocketFactory.getDefault(),
            connectTimeoutMs: Int = 3_000,
            handshakeTimeoutMs: Int = 8_000,
        ): ZirconiaTlsSession {
            val socket = socketFactory.createSocket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw when (e) {
                    is ConnectException, is SocketTimeoutException, is NoRouteToHostException ->
                        UnitConnectException.Unreachable("$host:$port did not answer", e)
                    else -> UnitConnectException.Unreachable("$host:$port: ${e.message}", e)
                }
            }
            socket.soTimeout = handshakeTimeoutMs
            val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
            val client = PskClient(Zirconia.PSK_IDENTITY.toByteArray(Charsets.US_ASCII), key)
            try {
                protocol.connect(client)
            } catch (e: TlsFatalAlertReceived) {
                runCatching { socket.close() }
                throw if (e.alertDescription in KEY_ALERTS) {
                    UnitConnectException.KeyRejected("the unit rejected the key (${AlertDescription.getText(e.alertDescription)})", e)
                } else {
                    UnitConnectException.Handshake("handshake failed (${AlertDescription.getText(e.alertDescription)})", e)
                }
            } catch (e: TlsFatalAlert) {
                runCatching { socket.close() }
                throw if (e.alertDescription == AlertDescription.bad_record_mac) {
                    UnitConnectException.KeyRejected("the unit's handshake did not verify with this key", e)
                } else {
                    UnitConnectException.Handshake("handshake failed (${AlertDescription.getText(e.alertDescription)})", e)
                }
            } catch (e: SocketTimeoutException) {
                runCatching { socket.close() }
                throw UnitConnectException.Handshake("the unit stopped answering during the handshake", e)
            } catch (e: IOException) {
                runCatching { socket.close() }
                // An ESP32 that dislikes the key usually just hangs up after the client Finished.
                val alert = client.receivedAlert
                throw if (alert != null && alert in KEY_ALERTS) {
                    UnitConnectException.KeyRejected("the unit rejected the key", e)
                } else {
                    UnitConnectException.Handshake("handshake failed: ${e.message ?: e.javaClass.simpleName}", e)
                }
            }
            return ZirconiaTlsSession(socket, protocol)
        }
    }
}
