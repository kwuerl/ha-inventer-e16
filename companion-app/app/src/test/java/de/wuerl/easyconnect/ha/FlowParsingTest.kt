// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ha

import de.wuerl.easyconnect.ble.EasyConnectGatt
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FlowParsingTest {
    private val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val data = UnitSetupData("192.0.2.10", 47820, "ABCDE-FGHIJ", key, "Easy Connect e16")

    @Test
    fun `parses a form as Home Assistant serialises it`() {
        val json = haJson.parseToJsonElement(
            """{"type":"form","flow_id":"f1","handler":"inventer_e16","step_id":"user",
               "data_schema":[{"name":"host","required":true,"type":"string"},
                              {"name":"device_id","required":true,"type":"string"},
                              {"name":"psk","required":true,"selector":{"text":{"type":"password"}}}],
               "errors":{"base":"cannot_connect"},"description_placeholders":null,"last_step":null}""",
        )
        val form = FlowResult.parse(json) as FlowResult.Form
        assertEquals("user", form.stepId)
        assertEquals(listOf("host", "device_id", "psk"), form.fields.map { it.name })
        assertEquals("cannot_connect", form.errors["base"])
    }

    @Test
    fun `fills the inventer_e16 form and renamed fields`() {
        val e16 = FlowInputMapper.map(
            listOf(FlowField("host", true, false), FlowField("device_id", true, false), FlowField("psk", true, false)),
            data,
        )
        assertEquals("192.0.2.10", e16["host"]!!.jsonPrimitive.content)
        assertEquals("ABCDE-FGHIJ", e16["device_id"]!!.jsonPrimitive.content)
        assertEquals("0102030405060708", e16["psk"]!!.jsonPrimitive.content)

        val renamed = FlowInputMapper.map(
            listOf(FlowField("host", true, true), FlowField("port", true, true), FlowField("psk_key", true, false), FlowField("name", false, false)),
            data,
        )
        assertEquals(47820, renamed["port"]!!.jsonPrimitive.int)
        assertEquals("0102030405060708", renamed["psk_key"]!!.jsonPrimitive.content)
        assertEquals("Easy Connect e16", renamed["name"]!!.jsonPrimitive.content)
    }

    @Test(expected = FlowInputMapper.MissingField::class)
    fun `an unknown required field cannot be filled`() {
        FlowInputMapper.map(listOf(FlowField("api_token", true, false)), data)
    }

    @Test
    fun `parses create_entry, abort and menu`() {
        val created = FlowResult.parse(haJson.parseToJsonElement("""{"type":"create_entry","title":"inVENTer Zone 1","result":{"entry_id":"abc"}}"""))
        assertEquals(FlowResult.CreateEntry(null, "inVENTer Zone 1", "abc"), created)
        assertEquals(FlowResult.Abort("f", "already_configured"), FlowResult.parse(haJson.parseToJsonElement("""{"type":"abort","flow_id":"f","reason":"already_configured"}""")))
        val menu = FlowResult.parse(haJson.parseToJsonElement("""{"type":"menu","flow_id":"m","step_id":"init","menu_options":["discover","user"]}"""))
        assertEquals(listOf("discover", "user"), (menu as FlowResult.Menu).options)
    }

    @Test
    fun `normalises Home Assistant addresses`() {
        assertEquals("http://homeassistant.local:8123/", HaUrls.normalize("homeassistant.local:8123/lovelace/0").toString())
        assertEquals("https://ha.example.org/", HaUrls.normalize(" https://ha.example.org/ ").toString())
        assertNull(HaUrls.normalize(""))
        assertEquals("http://192.0.2.20:8123", HaUrls.display(HaUrls.normalize("192.0.2.20:8123")!!))
    }

    @Test
    fun `recognises the controller and parses its device id`() {
        assertTrue(EasyConnectGatt.looksLikeEasyConnect("Easy Connect e16", emptyList()))
        assertTrue(EasyConnectGatt.looksLikeEasyConnect(null, listOf(EasyConnectGatt.AUTH_SERVICE)))
        assertFalse(EasyConnectGatt.looksLikeEasyConnect("Pixel Buds", listOf(UUID.randomUUID())))
        assertEquals("ABCDE-FGHIJ", EasyConnectGatt.parseDeviceId("ABCDE-FGHIJ".toByteArray() + ByteArray(5)))
        assertEquals("", EasyConnectGatt.parseDeviceId(ByteArray(11)))
    }
}
