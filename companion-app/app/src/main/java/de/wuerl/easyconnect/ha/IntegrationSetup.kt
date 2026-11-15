// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ha

import de.wuerl.easyconnect.device.AccessKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One field of a config-flow form, as serialised by voluptuous-serialize. */
data class FlowField(val name: String, val required: Boolean, val hasDefault: Boolean)

/** The result of a config-flow call (`data_entry_flow.FlowResult` as JSON). */
sealed interface FlowResult {
    val flowId: String?

    data class Form(
        override val flowId: String,
        val stepId: String,
        val fields: List<FlowField>,
        val errors: Map<String, String>,
    ) : FlowResult

    data class CreateEntry(override val flowId: String?, val title: String?, val entryId: String?) : FlowResult
    data class Abort(override val flowId: String?, val reason: String) : FlowResult
    data class Menu(override val flowId: String, val stepId: String, val options: List<String>) : FlowResult
    data class Other(override val flowId: String?, val type: String) : FlowResult

    companion object {
        fun parse(json: JsonElement): FlowResult {
            val o = json as? JsonObject ?: return Other(null, "unknown")
            val flowId = o.str("flow_id")
            return when (val type = o.str("type")) {
                "form" -> Form(
                    flowId = flowId.orEmpty(),
                    stepId = o.str("step_id").orEmpty(),
                    fields = o.arr("data_schema")?.mapNotNull { f ->
                        val field = f as? JsonObject ?: return@mapNotNull null
                        FlowField(
                            name = field.str("name") ?: return@mapNotNull null,
                            required = field.bool("required") == true,
                            hasDefault = field.containsKey("default"),
                        )
                    }.orEmpty(),
                    errors = o.obj("errors")?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap().orEmpty(),
                )
                "create_entry" -> CreateEntry(flowId, o.str("title"), o.obj("result")?.str("entry_id"))
                "abort" -> Abort(flowId, o.str("reason").orEmpty())
                "menu" -> Menu(
                    flowId = flowId.orEmpty(),
                    stepId = o.str("step_id").orEmpty(),
                    options = when (val m = o["menu_options"]) {
                        is JsonArray -> m.mapNotNull { it.str() }
                        is JsonObject -> m.keys.toList()
                        else -> emptyList()
                    },
                )
                else -> Other(flowId, type ?: "unknown")
            }
        }
    }
}

/** What the app knows about the unit, to be typed into the integration's forms. */
class UnitSetupData(
    val host: String,
    val port: Int,
    val deviceId: String,
    val key: ByteArray,
    val name: String? = null,
)

/**
 * Fills config-flow forms by field name: `host`, `device_id` and `psk` (as hex) of the
 * inventer_e16 forms. Names are matched loosely (`ip`, `port`, `*key`, `name`), so a renamed
 * field in a later version of the integration still gets its value.
 */
object FlowInputMapper {
    class MissingField(val field: String) : Exception("the form asks for '$field'")

    fun map(fields: List<FlowField>, data: UnitSetupData): JsonObject = buildJsonObject {
        for (field in fields) {
            val name = field.name.lowercase()
            when {
                name == "host" || name == "ip" || name == "ip_address" || name == "address" -> put(field.name, data.host)
                name == "port" -> put(field.name, data.port)
                name == "device_id" || name == "deviceid" || name == "identity" -> put(field.name, data.deviceId)
                name.contains("psk") || name.endsWith("key") -> put(field.name, AccessKey.hex(data.key))
                name == "name" -> data.name?.let { put(field.name, it) }
                field.required && !field.hasDefault -> throw MissingField(field.name)
            }
        }
    }
}

sealed interface SetupStep {
    data object Starting : SetupStep

    /** Home Assistant is connecting to the unit to check the key. */
    data object Validating : SetupStep
    data object UpdatingExisting : SetupStep
}

sealed interface SetupOutcome {
    data class Created(val title: String?, val entryId: String?) : SetupOutcome

    /** An existing entry got the new host/key (via reconfigure or a pending re-authentication). */
    data class Updated(val title: String?) : SetupOutcome

    /** Already set up and nothing could be (or had to be) changed. */
    data class AlreadyConfigured(val title: String?) : SetupOutcome
}

class SetupFailedException(val reason: Reason, val detail: String? = null) : Exception(detail ?: reason.name) {
    enum class Reason { NotAdmin, IntegrationMissing, CannotConnect, InvalidKey, UnsupportedForm, Aborted, Unexpected }
}

/**
 * Drives the integration's config flow the way the Home Assistant UI would:
 * 1. user step with host, device ID and PSK → a new entry;
 * 2. if Home Assistant already discovered the unit (DHCP), finish that discovery flow instead;
 * 3. if an entry exists, finish a pending re-authentication or run "reconfigure" so the
 *    entry gets the current host and key without losing entities or history.
 */
class IntegrationSetup(private val api: HomeAssistantApi) {

    suspend fun run(domain: String, data: UnitSetupData, onStep: (SetupStep) -> Unit = {}): SetupOutcome {
        onStep(SetupStep.Starting)
        val first = start(domain, entryId = null)
        val result = fill(first, data, onStep)
        return when (result) {
            is FlowResult.CreateEntry -> SetupOutcome.Created(result.title, result.entryId)
            is FlowResult.Abort -> when (result.reason) {
                "already_in_progress" -> finishDiscoveredFlow(domain, data, onStep)
                "already_configured" -> updateExisting(domain, data, onStep)
                else -> throw SetupFailedException(SetupFailedException.Reason.Aborted, result.reason)
            }
            else -> unexpected(result)
        }
    }

    private suspend fun start(domain: String, entryId: String?): FlowResult = try {
        api.startFlow(domain, entryId)
    } catch (e: HaException.Unauthorized) {
        throw SetupFailedException(SetupFailedException.Reason.NotAdmin, e.message)
    } catch (e: HaException.Http) {
        if (e.code == 404) throw SetupFailedException(SetupFailedException.Reason.IntegrationMissing, e.message)
        throw e
    }

    /** Answers menus and forms until the flow ends; a form with errors becomes an exception. */
    private suspend fun fill(initial: FlowResult, data: UnitSetupData, onStep: (SetupStep) -> Unit): FlowResult {
        var result = initial
        repeat(MAX_STEPS) {
            result = when (val r = result) {
                is FlowResult.Menu -> {
                    val option = r.options.firstOrNull { it == "user" || it == "manual" } ?: r.options.firstOrNull()
                        ?: return unexpected(r)
                    api.submitFlow(r.flowId, buildJsonObject { put("next_step_id", option) })
                }
                is FlowResult.Form -> {
                    if (r.errors.isNotEmpty() && r !== initial) {
                        api.abortFlow(r.flowId)
                        throw failureFor(r.errors)
                    }
                    val input = try {
                        FlowInputMapper.map(r.fields, data)
                    } catch (e: FlowInputMapper.MissingField) {
                        api.abortFlow(r.flowId)
                        throw SetupFailedException(SetupFailedException.Reason.UnsupportedForm, e.field)
                    }
                    onStep(SetupStep.Validating)
                    val next = api.submitFlow(r.flowId, input)
                    if (next is FlowResult.Form && next.errors.isNotEmpty()) {
                        api.abortFlow(next.flowId)
                        throw failureFor(next.errors)
                    }
                    next
                }
                else -> return r
            }
        }
        return result
    }

    private suspend fun finishDiscoveredFlow(domain: String, data: UnitSetupData, onStep: (SetupStep) -> Unit): SetupOutcome {
        val discovered = api.flowsInProgress().firstOrNull {
            it.handler == domain && it.uniqueId.equals(data.deviceId, ignoreCase = true) && it.source != "reauth"
        } ?: throw SetupFailedException(SetupFailedException.Reason.Aborted, "already_in_progress")
        return when (val result = fill(api.currentFlowStep(discovered.flowId), data, onStep)) {
            is FlowResult.CreateEntry -> SetupOutcome.Created(result.title, result.entryId)
            is FlowResult.Abort ->
                if (result.reason == "already_configured") updateExisting(domain, data, onStep)
                else throw SetupFailedException(SetupFailedException.Reason.Aborted, result.reason)
            else -> unexpected(result)
        }
    }

    private suspend fun updateExisting(domain: String, data: UnitSetupData, onStep: (SetupStep) -> Unit): SetupOutcome {
        onStep(SetupStep.UpdatingExisting)
        val entries = api.configEntries(domain)
        val entry = findEntry(domain, data.deviceId, entries) ?: return SetupOutcome.AlreadyConfigured(null)

        val reauth = runCatching { api.flowsInProgress() }.getOrDefault(emptyList())
            .firstOrNull { it.handler == domain && it.source == "reauth" && it.entryId == entry.entryId }
        if (reauth != null) {
            return finishUpdate(fill(api.currentFlowStep(reauth.flowId), data, onStep), entry)
        }
        if (!entry.supportsReconfigure) return SetupOutcome.AlreadyConfigured(entry.title)
        return finishUpdate(fill(start(domain, entry.entryId), data, onStep), entry)
    }

    private fun finishUpdate(result: FlowResult, entry: HaConfigEntry): SetupOutcome = when {
        result is FlowResult.Abort && result.reason in setOf("reconfigure_successful", "reauth_successful") ->
            SetupOutcome.Updated(entry.title)
        result is FlowResult.Abort -> throw SetupFailedException(SetupFailedException.Reason.Aborted, result.reason)
        else -> unexpected(result)
    }

    /** The entry whose controller device carries [deviceId]; else the only entry of the domain. */
    private suspend fun findEntry(domain: String, deviceId: String, entries: List<HaConfigEntry>): HaConfigEntry? {
        if (entries.size == 1) return entries.single()
        val ids = entries.associateBy { it.entryId }
        val device = runCatching { api.devices() }.getOrDefault(emptyList()).firstOrNull { device ->
            device.identifiers.any { (d, id) -> d == domain && id.equals(deviceId, ignoreCase = true) }
        }
        return device?.configEntries?.firstNotNullOfOrNull { ids[it] }
    }

    private fun failureFor(errors: Map<String, String>): SetupFailedException {
        val key = errors["base"] ?: errors.values.firstOrNull().orEmpty()
        val reason = when (key) {
            "cannot_connect" -> SetupFailedException.Reason.CannotConnect
            "invalid_key", "invalid_auth" -> SetupFailedException.Reason.InvalidKey
            else -> SetupFailedException.Reason.Unexpected
        }
        return SetupFailedException(reason, key)
    }

    private fun unexpected(result: FlowResult): Nothing =
        throw SetupFailedException(SetupFailedException.Reason.Unexpected, "unexpected flow result $result")

    private companion object {
        const val MAX_STEPS = 4
    }
}
