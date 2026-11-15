// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ha

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the app's Home Assistant code against a real Home Assistant with the inventer_e16 config
 * flow and tools/mock_e16.py on 127.0.0.1:47820, as started by tools/ha-test/start.sh.
 * Enabled by HA_TEST_URL, HA_TEST_USER and HA_TEST_PASSWORD (an admin account); skipped
 * without them unless REQUIRE_HA_TEST is set (CI).
 */
class HomeAssistantIntegrationTest {
    private val env = System.getenv()
    private val http = defaultHaHttpClient()
    private val clientId = "https://easyconnect-companion.localhost/"
    private val redirectUri = "https://easyconnect-companion.localhost/auth-callback"
    private val auth = HaAuthClient(http, clientId, redirectUri)
    private val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val domain = "inventer_e16"

    /** What the in-app WebView does: Home Assistant's login flow, then the redirect with a code. */
    private fun loginCode(base: HttpUrl, user: String, password: String): String {
        val json = "application/json".toMediaType()
        fun post(path: String, body: String) = http.newCall(
            Request.Builder().url(base.resolve(path)!!).post(body.toRequestBody(json)).build(),
        ).execute().use { r ->
            val text = r.body.string()
            assertTrue("POST $path -> HTTP ${r.code}: $text", r.isSuccessful)
            haJson.parseToJsonElement(text).jsonObject
        }
        val flow = post(
            "/auth/login_flow",
            buildJsonObject {
                put("client_id", clientId)
                put("handler", JsonArray(listOf(JsonPrimitive("homeassistant"), JsonNull)))
                put("redirect_uri", redirectUri)
            }.toString(),
        )
        val done = post(
            "/auth/login_flow/${flow.str("flow_id")}",
            buildJsonObject { put("client_id", clientId); put("username", user); put("password", password) }.toString(),
        )
        assertEquals("create_entry", done.str("type"))
        return done.str("result")!!
    }

    private suspend fun session(base: HttpUrl, user: String, password: String): Pair<HomeAssistantApi, HaTokenSource> {
        val tokens = auth.exchangeCode(base, loginCode(base, user, password))
        assertNotNull(tokens.refreshToken)
        val source = HaTokenSource(auth, base, tokens)
        return HomeAssistantApi(http, base, source) to source
    }

    private suspend fun service(api: HomeAssistantApi, base: HttpUrl, source: HaTokenSource, name: String, data: String = "{}") {
        http.newCall(
            Request.Builder().url(base.resolve("/api/services/test_helpers/$name")!!)
                .header("Authorization", "Bearer ${source.accessToken()}")
                .post(data.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { assertTrue("service $name -> ${it.code}", it.isSuccessful) }
        Thread.sleep(400)
    }

    private fun unit(host: String = "127.0.0.1", key: ByteArray = this.key) =
        UnitSetupData(host = host, port = 47820, deviceId = "ABCDE-FGHIJ", key = key, name = "Easy Connect e16")

    private suspend fun expectFailure(reason: SetupFailedException.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: SetupFailedException) {
            assertEquals(e.detail, reason, e.reason)
        }
    }

    @Test
    fun `sets up ha-inventer-e16 end to end`() = runBlocking {
        val url = env["HA_TEST_URL"]
        check(url != null || env["REQUIRE_HA_TEST"] == null) { "REQUIRE_HA_TEST: HA_TEST_URL not set" }
        assumeTrue("HA_TEST_URL not set", url != null)
        val base = HaUrls.normalize(url!!)!!
        auth.probe(base)

        val (api, source) = session(base, env.getValue("HA_TEST_USER"), env.getValue("HA_TEST_PASSWORD"))
        assertEquals("Test Home", api.instance().locationName)
        assertTrue(api.currentUser().isAdmin)
        assertTrue(domain in api.flowHandlers())
        service(api, base, source, "remove_entries")
        val setup = IntegrationSetup(api)

        // 1. wrong key and unreachable unit are reported by Home Assistant's own check
        expectFailure(SetupFailedException.Reason.InvalidKey) { setup.run(domain, unit(key = ByteArray(8) { 7 })) }
        expectFailure(SetupFailedException.Reason.CannotConnect) { setup.run(domain, unit(host = "127.0.0.2")) }

        // 2. a fresh entry
        val steps = mutableListOf<SetupStep>()
        val created = setup.run(domain, unit()) { steps += it }
        assertEquals(SetupOutcome.Created::class, created::class)
        assertEquals("inVENTer Zone 1", (created as SetupOutcome.Created).title)
        assertTrue(SetupStep.Validating in steps)

        // 3. pairing again updates the existing entry through "reconfigure"
        val again = setup.run(domain, unit())
        assertEquals(SetupOutcome.Updated("inVENTer Zone 1"), again)
        assertEquals(1, api.configEntries(domain).size)

        // 4. a pending re-authentication is completed instead
        service(api, base, source, "start_reauth")
        assertTrue(api.flowsInProgress().any { it.handler == domain && it.source == "reauth" })
        assertEquals(SetupOutcome.Updated("inVENTer Zone 1"), setup.run(domain, unit()))
        assertFalse(api.flowsInProgress().any { it.handler == domain && it.source == "reauth" })

        // 5. Home Assistant discovered the unit via DHCP first: the app finishes that flow
        service(api, base, source, "remove_entries")
        service(api, base, source, "start_dhcp", """{"ip": "127.0.0.1", "hostname": "easy connect e16-abcde-fghij"}""")
        assertTrue(api.flowsInProgress().any { it.handler == domain && it.source == "dhcp" })
        val viaDiscovery = setup.run(domain, unit())
        assertTrue("got $viaDiscovery", viaDiscovery is SetupOutcome.Created)
        assertFalse(api.flowsInProgress().any { it.handler == domain })

        // 6. a non-admin account can sign in but cannot set up integrations
        val password = "not-an-admin-123"
        val username = "guest${System.nanoTime() % 100000}"
        HaWebSocket.open(http, base, source.accessToken()).use { ws ->
            val user = ws.call(
                "config/auth/create",
                mapOf("name" to JsonPrimitive("Guest"), "group_ids" to JsonArray(listOf(JsonPrimitive("system-users")))),
            ).jsonObject.obj("user")!!
            ws.call(
                "config/auth_provider/homeassistant/create",
                mapOf("user_id" to JsonPrimitive(user.str("id")), "username" to JsonPrimitive(username), "password" to JsonPrimitive(password)),
            )
        }
        val (guestApi, _) = session(base, username, password)
        assertFalse(guestApi.currentUser().isAdmin)
        expectFailure(SetupFailedException.Reason.NotAdmin) { IntegrationSetup(guestApi).run(domain, unit()) }

        // 7. tokens refresh, and a revoked session is reported as expired
        source.accessToken(forceRefresh = true)
        auth.revoke(base, source.tokens.refreshToken!!)
        try {
            source.accessToken(forceRefresh = true)
            fail("refresh after revoke must fail")
        } catch (e: HaException.SessionExpired) {
            // expected
        }
    }
}
