// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Problems talking to Home Assistant, with enough detail for a helpful message. */
sealed class HaException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Unreachable(message: String, cause: Throwable? = null) : HaException(message, cause)
    class NotHomeAssistant(message: String) : HaException(message)

    /** The access token is invalid or the user lacks the rights (admin) for the call. */
    class Unauthorized(message: String) : HaException(message)

    /** The refresh token was revoked; the user must sign in again. */
    class SessionExpired(message: String) : HaException(message)
    class Http(val code: Int, message: String) : HaException(message)
    class Api(val code: String?, message: String) : HaException(message)
}

internal val haJson = Json { ignoreUnknownKeys = true }

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.str(key: String): String? = this[key].str()
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

object HaUrls {
    /** Normalises user input such as "homeassistant.local:8123/lovelace" to a base URL. */
    fun normalize(input: String): HttpUrl? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }

    fun display(url: HttpUrl): String = url.toString().trimEnd('/')
}

fun defaultHaHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    // Config-flow steps make Home Assistant talk to the unit, which can take a while.
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

/** Tokens from Home Assistant's OAuth2 token endpoint. */
data class HaTokens(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long)

/**
 * Home Assistant's IndieAuth-flavoured OAuth2 (authorization code grant):
 * `/auth/authorize` in a browser, then `/auth/token` for tokens. The client id is a URL and
 * the redirect URI must share its scheme and host.
 */
class HaAuthClient(
    private val http: OkHttpClient,
    val clientId: String,
    val redirectUri: String,
) {
    fun authorizeUrl(base: HttpUrl, state: String): HttpUrl = base.resolve("/auth/authorize")!!.newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("state", state)
        .build()

    /** Result of the browser step, parsed from the redirect. */
    sealed interface Callback {
        data class Code(val code: String, val state: String?) : Callback
        data class Error(val error: String) : Callback
    }

    fun parseCallback(url: String): Callback? {
        if (!url.startsWith(redirectUri)) return null
        val parsed = url.toHttpUrlOrNull() ?: return Callback.Error("malformed redirect")
        parsed.queryParameter("error")?.let { return Callback.Error(it) }
        val code = parsed.queryParameter("code") ?: return Callback.Error("no authorization code")
        return Callback.Code(code, parsed.queryParameter("state"))
    }

    /** Confirms [base] is a Home Assistant server (the login providers list needs no auth). */
    suspend fun probe(base: HttpUrl) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base.resolve("/auth/providers")!!).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw HaException.Unreachable("${HaUrls.display(base)} is not reachable: ${e.message}", e)
        }
        response.use {
            val body = it.body.string()
            val looksRight = it.isSuccessful && runCatching {
                val json = haJson.parseToJsonElement(body)
                json is JsonArray || (json is JsonObject && json.containsKey("providers"))
            }.getOrDefault(false)
            if (!looksRight) throw HaException.NotHomeAssistant("${HaUrls.display(base)} does not look like Home Assistant")
        }
    }

    suspend fun exchangeCode(base: HttpUrl, code: String): HaTokens = token(
        base,
        FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("client_id", clientId).build(),
        previousRefresh = null,
    )

    suspend fun refresh(base: HttpUrl, refreshToken: String): HaTokens = token(
        base,
        FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).add("client_id", clientId).build(),
        previousRefresh = refreshToken,
    )

    /** Revokes the refresh token (and every access token issued from it). Best effort. */
    suspend fun revoke(base: HttpUrl, refreshToken: String) = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("token", refreshToken).build()
        runCatching { http.newCall(Request.Builder().url(base.resolve("/auth/revoke")!!).post(body).build()).execute().close() }
        Unit
    }

    private suspend fun token(base: HttpUrl, form: FormBody, previousRefresh: String?): HaTokens = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base.resolve("/auth/token")!!).post(form).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw HaException.Unreachable("Home Assistant is not reachable: ${e.message}", e)
        }
        response.use {
            val body = it.body.string()
            val json = runCatching { haJson.parseToJsonElement(body).jsonObject }.getOrNull()
            if (!it.isSuccessful || json == null) {
                val error = json?.str("error")
                if (previousRefresh != null && (error == "invalid_grant" || it.code == 403)) {
                    throw HaException.SessionExpired("Home Assistant ended the session")
                }
                throw HaException.Api(error, json?.str("error_description") ?: "token request failed (HTTP ${it.code})")
            }
            val access = json.str("access_token") ?: throw HaException.Api(null, "no access token in reply")
            val expiresIn = (json["expires_in"] as? JsonPrimitive)?.longOrNull ?: 1800L
            HaTokens(access, json.str("refresh_token") ?: previousRefresh, System.currentTimeMillis() + expiresIn * 1000)
        }
    }
}

/** Keeps an access token fresh using the refresh token. */
class HaTokenSource(
    private val auth: HaAuthClient,
    private val base: HttpUrl,
    initial: HaTokens,
    private val onRefreshed: (HaTokens) -> Unit = {},
) {
    private val lock = Mutex()
    @Volatile var tokens: HaTokens = initial
        private set

    suspend fun accessToken(forceRefresh: Boolean = false): String = lock.withLock {
        val current = tokens
        val stale = current.expiresAtMillis - System.currentTimeMillis() < 60_000
        if (!forceRefresh && !stale) return current.accessToken
        val refreshToken = current.refreshToken ?: throw HaException.SessionExpired("no refresh token")
        val renewed = auth.refresh(base, refreshToken)
        tokens = renewed
        onRefreshed(renewed)
        renewed.accessToken
    }
}

data class HaUser(val id: String?, val name: String?, val isAdmin: Boolean, val isOwner: Boolean)
data class HaInstance(val locationName: String?, val version: String?)
data class HaConfigEntry(val entryId: String, val domain: String, val title: String?, val state: String?, val supportsReconfigure: Boolean)
data class HaFlowInProgress(val flowId: String, val handler: String, val stepId: String?, val source: String?, val uniqueId: String?, val entryId: String?)
data class HaDevice(val id: String, val configEntries: List<String>, val identifiers: List<Pair<String, String>>)

/** The REST and WebSocket calls the app needs; all suspend and run on the IO dispatcher. */
class HomeAssistantApi(
    private val http: OkHttpClient,
    val base: HttpUrl,
    private val tokens: HaTokenSource,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun instance(): HaInstance {
        val json = get("/api/config").jsonObject
        return HaInstance(json.str("location_name"), json.str("version"))
    }

    suspend fun flowHandlers(): Set<String> = get("/api/config/config_entries/flow_handlers").jsonArray.mapNotNull { it.str() }.toSet()

    suspend fun configEntries(domain: String): List<HaConfigEntry> =
        get("/api/config/config_entries/entry?domain=$domain").jsonArray.mapNotNull { element ->
            val e = element as? JsonObject ?: return@mapNotNull null
            HaConfigEntry(
                entryId = e.str("entry_id") ?: return@mapNotNull null,
                domain = e.str("domain") ?: domain,
                title = e.str("title"),
                state = e.str("state"),
                supportsReconfigure = e.bool("supports_reconfigure") ?: false,
            )
        }

    suspend fun startFlow(handler: String, entryId: String? = null): FlowResult {
        val body = buildJsonObject {
            put("handler", handler)
            put("show_advanced_options", false)
            if (entryId != null) put("entry_id", entryId)
        }
        return FlowResult.parse(post("/api/config/config_entries/flow", body))
    }

    /** Re-reads the current step of a flow (used for flows started by Home Assistant). */
    suspend fun currentFlowStep(flowId: String): FlowResult = FlowResult.parse(get("/api/config/config_entries/flow/$flowId"))

    suspend fun submitFlow(flowId: String, input: JsonObject): FlowResult =
        FlowResult.parse(post("/api/config/config_entries/flow/$flowId", input))

    suspend fun abortFlow(flowId: String) {
        runCatching { send(Request.Builder().url(base.resolve("/api/config/config_entries/flow/$flowId")!!).delete()) }
    }

    suspend fun currentUser(): HaUser = webSocket { ws ->
        val user = ws.call("auth/current_user").jsonObject
        HaUser(user.str("id"), user.str("name"), user.bool("is_admin") ?: false, user.bool("is_owner") ?: false)
    }

    /** Flows started by Home Assistant itself (discovery, re-authentication). Admin only. */
    suspend fun flowsInProgress(): List<HaFlowInProgress> = webSocket { ws ->
        ws.call("config_entries/flow/progress").jsonArray.mapNotNull { element ->
            val f = element as? JsonObject ?: return@mapNotNull null
            val context = f.obj("context")
            HaFlowInProgress(
                flowId = f.str("flow_id") ?: return@mapNotNull null,
                handler = f.str("handler") ?: return@mapNotNull null,
                stepId = f.str("step_id"),
                source = context?.str("source"),
                uniqueId = context?.str("unique_id"),
                entryId = context?.str("entry_id"),
            )
        }
    }

    suspend fun devices(): List<HaDevice> = webSocket { ws ->
        ws.call("config/device_registry/list").jsonArray.mapNotNull { element ->
            val d = element as? JsonObject ?: return@mapNotNull null
            HaDevice(
                id = d.str("id") ?: return@mapNotNull null,
                configEntries = d.arr("config_entries")?.mapNotNull { it.str() }.orEmpty(),
                identifiers = d.arr("identifiers")?.mapNotNull { pair ->
                    val p = pair as? JsonArray ?: return@mapNotNull null
                    val domain = p.getOrNull(0).str() ?: return@mapNotNull null
                    val id = p.getOrNull(1).str() ?: return@mapNotNull null
                    domain to id
                }.orEmpty(),
            )
        }
    }

    // --- plumbing -------------------------------------------------------------------------

    private suspend fun get(path: String): JsonElement = send(Request.Builder().url(base.resolve(path)!!).get())

    private suspend fun post(path: String, body: JsonObject): JsonElement =
        send(Request.Builder().url(base.resolve(path)!!).post(body.toString().toRequestBody(jsonType)))

    private suspend fun send(builder: Request.Builder): JsonElement = withContext(Dispatchers.IO) {
        var response = execute(builder, tokens.accessToken())
        if (response.code == 401) {
            response.close()
            response = execute(builder, tokens.accessToken(forceRefresh = true))
        }
        response.use { parse(it) }
    }

    private fun execute(builder: Request.Builder, token: String): Response = try {
        http.newCall(builder.header("Authorization", "Bearer $token").build()).execute()
    } catch (e: IOException) {
        throw HaException.Unreachable("Home Assistant is not reachable: ${e.message}", e)
    }

    private fun parse(response: Response): JsonElement {
        val text = response.body.string()
        val json = runCatching { if (text.isBlank()) JsonNull else haJson.parseToJsonElement(text) }.getOrNull()
        val message = (json as? JsonObject)?.str("message")
        when {
            response.isSuccessful -> return json ?: JsonNull
            response.code == 401 || response.code == 403 -> throw HaException.Unauthorized(message ?: "unauthorized")
            else -> throw HaException.Http(response.code, message ?: "HTTP ${response.code}")
        }
    }

    private suspend fun <T> webSocket(block: suspend (HaWebSocket) -> T): T {
        val token = tokens.accessToken()
        return HaWebSocket.open(http, base, token).use { block(it) }
    }
}

/** A short-lived, authenticated Home Assistant WebSocket connection. */
class HaWebSocket private constructor(
    private val socket: WebSocket,
    private val incoming: Channel<JsonObject>,
) : Closeable {
    private var nextId = 1

    suspend fun call(type: String, payload: Map<String, JsonElement> = emptyMap(), timeoutMs: Long = 15_000): JsonElement {
        val id = nextId++
        socket.send(
            buildJsonObject {
                put("id", id)
                put("type", type)
                payload.forEach { (k, v) -> put(k, v) }
            }.toString(),
        )
        return withTimeout(timeoutMs) {
            var result: JsonElement? = null
            while (result == null) {
                val message = incoming.receive()
                if ((message["id"] as? JsonPrimitive)?.intOrNull != id || message.str("type") != "result") continue
                if (message.bool("success") == true) {
                    result = message["result"] ?: JsonNull
                } else {
                    val error = message.obj("error")
                    if (error?.str("code") == "unauthorized") throw HaException.Unauthorized(error.str("message") ?: "unauthorized")
                    throw HaException.Api(error?.str("code"), error?.str("message") ?: "$type failed")
                }
            }
            result
        }
    }

    override fun close() {
        socket.close(1000, null)
        incoming.close()
    }

    companion object {
        suspend fun open(http: OkHttpClient, base: HttpUrl, accessToken: String): HaWebSocket {
            val incoming = Channel<JsonObject>(Channel.UNLIMITED)
            val listener = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val json = runCatching { haJson.parseToJsonElement(text) }.getOrNull()) {
                        is JsonObject -> incoming.trySend(json)
                        is JsonArray -> json.forEach { (it as? JsonObject)?.let(incoming::trySend) }
                        else -> Unit
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    incoming.close(HaException.Unreachable("WebSocket failed: ${t.message}", t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.close()
                }
            }
            val socket = http.newWebSocket(Request.Builder().url(base.resolve("/api/websocket")!!).build(), listener)
            try {
                withTimeout(15_000) {
                    while (incoming.receive().str("type") != "auth_required") { /* wait for the greeting */ }
                    socket.send(buildJsonObject { put("type", "auth"); put("access_token", accessToken) }.toString())
                    while (true) {
                        when (incoming.receive().str("type")) {
                            "auth_ok" -> break
                            "auth_invalid" -> throw HaException.Unauthorized("WebSocket authentication failed")
                        }
                    }
                }
            } catch (e: Exception) {
                socket.cancel()
                throw e
            }
            return HaWebSocket(socket, incoming)
        }
    }
}
