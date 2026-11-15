// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalNetworkGateTest {
    private var granted = false
    private val published = mutableListOf<LocalNetworkUi?>()
    private val gate = LocalNetworkGate({ granted }) { published += it }
    private val ran = mutableListOf<String>()

    @Test
    fun `runs at once when the permission is there`() {
        granted = true
        gate.run("locate") { ran += "locate" }
        assertEquals(listOf("locate"), ran)
        assertEquals(emptyList<LocalNetworkUi?>(), published)
    }

    @Test
    fun `holds the work back and asks right away`() {
        var blocked = 0
        gate.run("locate", blocked = { blocked++ }) { ran += "locate" }
        assertEquals(emptyList<String>(), ran)
        assertEquals(1, blocked)
        assertEquals(LocalNetworkUi.Request, gate.state)

        gate.onRequesting()
        gate.run("ha-status") { ran += "ha-status" }
        gate.run("locate") { ran += "locate again" }
        assertEquals("no second dialog while one is open", LocalNetworkUi.Requesting, gate.state)

        granted = true
        gate.onResult(granted = true, canAskAgain = true)
        assertEquals("one run per key, the latest wins", listOf("locate again", "ha-status"), ran)
        assertNull(gate.state)
    }

    @Test
    fun `a denial keeps the work until it is allowed in the settings`() {
        gate.run("locate") { ran += "locate" }
        gate.onRequesting()
        gate.onResult(granted = false, canAskAgain = false)
        assertEquals(LocalNetworkUi.Denied(canAskAgain = false), gate.state)

        gate.recheck()
        assertEquals(emptyList<String>(), ran)

        granted = true
        gate.recheck()
        assertEquals(listOf("locate"), ran)
        assertNull(gate.state)
    }

    @Test
    fun `allow asks again, and nothing runs twice`() {
        gate.run("locate") { ran += "locate" }
        gate.onResult(granted = false, canAskAgain = true)
        gate.ask()
        assertEquals(LocalNetworkUi.Request, gate.state)
        granted = true
        gate.onResult(granted = true, canAskAgain = true)
        gate.onResult(granted = true, canAskAgain = true)
        gate.recheck()
        assertEquals(listOf("locate"), ran)
    }
}
