// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.device

/** Text forms of the unit's TLS pre-shared key (raw bytes of characteristic `638ff62c…8ad`). */
object AccessKey {
    fun hex(key: ByteArray): String = key.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun isBlank(key: ByteArray?): Boolean = key == null || key.isEmpty() || key.all { it == 0.toByte() }
}
