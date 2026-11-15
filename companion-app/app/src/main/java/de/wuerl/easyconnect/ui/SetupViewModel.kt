// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wuerl.easyconnect.AppGraph
import de.wuerl.easyconnect.R
import de.wuerl.easyconnect.ble.BleException
import de.wuerl.easyconnect.ble.FoundUnit
import de.wuerl.easyconnect.ble.PairingModeTimeoutException
import de.wuerl.easyconnect.ble.PairingProgress
import de.wuerl.easyconnect.ble.UnitCredentials
import de.wuerl.easyconnect.data.HaSession
import de.wuerl.easyconnect.data.IntegrationRecord
import de.wuerl.easyconnect.data.PairedUnit
import de.wuerl.easyconnect.device.LocateOutcome
import de.wuerl.easyconnect.device.ProbeResult
import de.wuerl.easyconnect.device.Zirconia
import de.wuerl.easyconnect.ha.HaException
import de.wuerl.easyconnect.ha.HaTokenSource
import de.wuerl.easyconnect.ha.HaTokens
import de.wuerl.easyconnect.ha.HaUrls
import de.wuerl.easyconnect.ha.HomeAssistantApi
import de.wuerl.easyconnect.ha.IntegrationSetup
import de.wuerl.easyconnect.ha.SetupFailedException
import de.wuerl.easyconnect.ha.SetupOutcome
import de.wuerl.easyconnect.ha.SetupStep
import de.wuerl.easyconnect.ha.UnitSetupData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.SecureRandom
import java.util.Base64

/**
 * Orchestrates the three setup steps. As soon as the controller is paired (with a Wi-Fi
 * address) and Home Assistant is signed in with the integration available, the integration is
 * configured automatically — once per combination of unit, address, key and Home Assistant.
 */
class SetupViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    @Volatile private var unit: PairedUnit? = graph.store.loadUnit()
    @Volatile private var session: HaSession? = graph.store.loadHa()
    private var api: HomeAssistantApi? = null
    private var record: IntegrationRecord? = graph.store.loadIntegration()

    private var scanJob: Job? = null
    private var pairingJob: Job? = null
    private var locateJob: Job? = null
    private var haDiscoveryJob: Job? = null
    private var haStatusJob: Job? = null
    private var configureJob: Job? = null
    private var attemptedFingerprint: String? = null

    init {
        unit?.let { u ->
            val lan = u.host?.let { LanUi.Found(it, u.port, u.zones, u.hostVerified) }
            _state.update { it.copy(unit = UnitUi.Paired(u, lan ?: LanUi.NotFound(emptyList()))) }
            if (lan == null) locateUnit()
        }
        session?.let(::restoreSession)
        record?.let { r ->
            if (r.fingerprint == fingerprintFor(r.domain)) {
                _state.update { it.copy(integration = doneFromRecord(r)) }
            }
        }
    }

    // --- Pairing ---------------------------------------------------------------------------

    /** Opens the pairing sheet; the UI reports permission/Bluetooth/location status. */
    fun openPairing(hasPermissions: Boolean, bluetoothOn: Boolean, locationOk: Boolean) =
        onPairingPrerequisites(hasPermissions, bluetoothOn, locationOk)

    fun onPairingPrerequisites(hasPermissions: Boolean, bluetoothOn: Boolean, locationOk: Boolean) {
        val next = when {
            !graph.ble.isSupported -> PairingUi.Failed(UiText.res(R.string.pairing_error_generic, "Bluetooth LE not available"))
            !hasPermissions -> PairingUi.NeedsPermission
            !bluetoothOn -> PairingUi.BluetoothOff
            !locationOk -> PairingUi.LocationOff
            else -> null
        }
        if (next != null) {
            _state.update { it.copy(pairing = next) }
        } else {
            startScan()
        }
    }

    fun onPermissionDenied() = _state.update { it.copy(pairing = PairingUi.PermissionDenied) }

    private fun startScan() {
        stopPairingJobs()
        _state.update { it.copy(pairing = PairingUi.Scanning(emptyList())) }
        scanJob = viewModelScope.launch {
            var lastCount = 0
            var stableSince = System.currentTimeMillis()
            val picked: FoundUnit? = try {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    graph.ble.scan()
                        .onEach { units ->
                            _state.update { it.copy(pairing = PairingUi.Scanning(units)) }
                            if (units.size != lastCount) {
                                lastCount = units.size
                                stableSince = System.currentTimeMillis()
                            }
                        }
                        // One controller in range (the usual case): connect without asking.
                        .first { units -> units.size == 1 && System.currentTimeMillis() - stableSince >= AUTO_SELECT_AFTER_MS }
                        .single()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(pairing = PairingUi.Failed(bleError(e))) }
                return@launch
            }
            if (picked != null) {
                selectUnit(picked)
            } else if ((_state.value.pairing as? PairingUi.Scanning)?.found.isNullOrEmpty()) {
                _state.update { it.copy(pairing = PairingUi.Failed(UiText.res(R.string.pairing_error_not_found))) }
            } // several controllers: the list stays up for the user to pick
        }
    }

    fun selectUnit(unit: FoundUnit) {
        scanJob?.cancel()
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            _state.update { it.copy(pairing = PairingUi.Connecting(unit, reconnecting = false)) }
            val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
            try {
                val credentials = graph.ble.readCredentials(unit, PAIRING_TIMEOUT_MS) { progress ->
                    val ui = when (progress) {
                        PairingProgress.Connecting -> PairingUi.Connecting(unit, reconnecting = false)
                        PairingProgress.Reconnecting -> PairingUi.Connecting(unit, reconnecting = true)
                        is PairingProgress.WaitingForPairingMode -> PairingUi.WaitingForPairingMode(unit, progress.info, deadline)
                        PairingProgress.ReadingKey -> PairingUi.Reading(unit)
                    }
                    _state.update { it.copy(pairing = ui) }
                }
                onPaired(credentials)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(pairing = PairingUi.Failed(bleError(e))) }
            }
        }
    }

    private suspend fun onPaired(credentials: UnitCredentials) {
        val previous = unit
        val paired = PairedUnit(
            bleAddress = credentials.address,
            bleName = credentials.bleName,
            deviceId = credentials.deviceId,
            key = credentials.key,
            model = credentials.info.model,
            serial = credentials.info.serial,
            firmware = credentials.info.firmware,
            pairedAt = System.currentTimeMillis(),
            // Same controller paired again: keep the known address until the search confirms it.
            host = previous?.takeIf { it.deviceId == credentials.deviceId }?.host,
            port = previous?.port ?: Zirconia.TCP_PORT,
        )
        saveUnit(paired, LanUi.Searching(null))
        _state.update { it.copy(pairing = PairingUi.Success(credentials.deviceId)) }
        attemptedFingerprint = null
        locateUnit()
        delay(SUCCESS_LINGER_MS)
        if (_state.value.pairing is PairingUi.Success) closePairing()
    }

    fun retryPairing() = startScan()

    fun closePairing() {
        stopPairingJobs()
        _state.update { it.copy(pairing = null) }
    }

    private fun stopPairingJobs() {
        scanJob?.cancel()
        pairingJob?.cancel()
    }

    private fun bleError(e: Throwable): UiText = when (e) {
        is PairingModeTimeoutException -> UiText.res(R.string.pairing_error_timeout)
        is BleException -> UiText.res(R.string.pairing_error_generic, e.message.orEmpty())
        is SecurityException -> UiText.res(R.string.pairing_status_denied)
        else -> UiText.res(R.string.pairing_error_generic, e.message ?: e.javaClass.simpleName)
    }

    // --- Controller on the LAN ------------------------------------------------------------

    fun locateUnit() {
        val current = unit ?: return
        locateJob?.cancel()
        locateJob = viewModelScope.launch {
            setLan(LanUi.Searching(null))
            val outcome = try {
                graph.locator.locate(current.key) { stage -> setLan(LanUi.Searching(stage)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocateOutcome.NotFound(emptyList())
            }
            when (outcome) {
                is LocateOutcome.Found -> {
                    val e = outcome.endpoint
                    val updated = (unit ?: return@launch).copy(
                        host = e.host, port = e.port, hostVerified = true, zones = e.zones.map { it.name.ifBlank { "Zone ${it.zoneId}" } },
                    )
                    saveUnit(updated, LanUi.Found(e.host, e.port, updated.zones, verified = true))
                }
                is LocateOutcome.NotFound -> {
                    // Keep a previously known address: Home Assistant might still reach it.
                    val known = unit?.host
                    setLan(
                        if (known != null && outcome.rejectedBy.isEmpty()) {
                            LanUi.Found(known, unit!!.port, unit!!.zones, verified = false)
                        } else {
                            LanUi.NotFound(outcome.rejectedBy)
                        },
                    )
                }
                LocateOutcome.NoNetwork -> setLan(LanUi.NoWifi)
            }
            maybeAutoConfigure()
        }
    }

    fun openManualHost() = _state.update { it.copy(manualHost = ManualHostUi(unit?.host.orEmpty())) }
    fun dismissManualHost() = _state.update { it.copy(manualHost = null) }
    fun onManualHostChange(text: String) = _state.update {
        it.copy(manualHost = it.manualHost?.copy(input = text, error = null, offerUseAnyway = false))
    }

    fun checkManualHost() {
        val current = unit ?: return
        val host = _state.value.manualHost?.input?.trim().orEmpty()
        if (host.isEmpty()) return
        _state.update { it.copy(manualHost = it.manualHost?.copy(checking = true, error = null)) }
        locateJob?.cancel()
        locateJob = viewModelScope.launch {
            val result = try {
                graph.locator.probe(host, current.port, current.key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProbeResult.Failed(host, current.port, e.message.orEmpty())
            }
            when (result) {
                is ProbeResult.Verified -> {
                    val zones = result.endpoint.zones.map { it.name.ifBlank { "Zone ${it.zoneId}" } }
                    useHost(host, verified = true, zones = zones)
                }
                is ProbeResult.KeyRejected -> _state.update {
                    it.copy(manualHost = it.manualHost?.copy(checking = false, error = UiText.res(R.string.manual_host_rejected)))
                }
                else -> _state.update {
                    it.copy(manualHost = it.manualHost?.copy(checking = false, error = UiText.res(R.string.manual_host_unreachable), offerUseAnyway = true))
                }
            }
        }
    }

    fun useManualHostAnyway() {
        val host = _state.value.manualHost?.input?.trim().orEmpty()
        if (host.isNotEmpty()) useHost(host, verified = false, zones = emptyList())
    }

    private fun useHost(host: String, verified: Boolean, zones: List<String>) {
        val updated = (unit ?: return).copy(host = host, hostVerified = verified, zones = zones)
        saveUnit(updated, LanUi.Found(host, updated.port, zones, verified))
        _state.update { it.copy(manualHost = null) }
        maybeAutoConfigure()
    }

    fun toggleShowKey(show: Boolean) = _state.update { it.copy(showKey = show) }
    fun askForget(show: Boolean) = _state.update { it.copy(confirmForget = show) }

    fun forgetUnit() {
        locateJob?.cancel()
        unit = null
        graph.store.saveUnit(null)
        _state.update { it.copy(unit = UnitUi.NotPaired, confirmForget = false, showKey = false, integration = IntegrationUi.Waiting) }
    }

    private fun saveUnit(updated: PairedUnit, lan: LanUi) {
        unit = updated
        graph.store.saveUnit(updated)
        _state.update { it.copy(unit = UnitUi.Paired(updated, lan)) }
        refreshIntegrationCard()
    }

    private fun setLan(lan: LanUi) {
        val u = unit ?: return
        _state.update { it.copy(unit = UnitUi.Paired(u, lan)) }
    }

    // --- Home Assistant -------------------------------------------------------------------

    fun openHaPicker() {
        _state.update { it.copy(haPicker = HaPickerUi(input = session?.baseUrl ?: graph.store.lastHaUrl().orEmpty())) }
        haDiscoveryJob?.cancel()
        haDiscoveryJob = viewModelScope.launch {
            launch {
                delay(HA_DISCOVERY_SPINNER_MS)
                _state.update { it.copy(haPicker = it.haPicker?.copy(searching = false)) }
            }
            graph.haDiscovery.discover()
                .catch { _state.update { it.copy(haPicker = it.haPicker?.copy(searching = false)) } }
                .collect { found ->
                    _state.update { s ->
                        val picker = s.haPicker ?: return@update s
                        val input = if (picker.input.isBlank() && found.size == 1) found.single().url else picker.input
                        s.copy(haPicker = picker.copy(discovered = found, input = input))
                    }
                }
        }
    }

    fun onHaInputChange(text: String) = _state.update { it.copy(haPicker = it.haPicker?.copy(input = text, error = null)) }

    fun closeHaPicker() {
        haDiscoveryJob?.cancel()
        _state.update { it.copy(haPicker = null) }
    }

    fun connectHa(url: String = _state.value.haPicker?.input.orEmpty()) {
        val base = HaUrls.normalize(url)
        if (base == null) {
            _state.update { it.copy(haPicker = it.haPicker?.copy(error = UiText.res(R.string.error_bad_url))) }
            return
        }
        _state.update { it.copy(haPicker = it.haPicker?.copy(input = HaUrls.display(base), checking = true, error = null)) }
        viewModelScope.launch {
            try {
                graph.haAuth.probe(base)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = if (e is HaException.NotHomeAssistant) R.string.error_not_ha else R.string.error_ha_address_unreachable
                _state.update { it.copy(haPicker = it.haPicker?.copy(checking = false, error = UiText.res(error))) }
                return@launch
            }
            haDiscoveryJob?.cancel()
            val state = randomState()
            _state.update {
                it.copy(
                    haPicker = null,
                    haLogin = HaLoginUi(graph.haAuth.authorizeUrl(base, state).toString(), HaUrls.display(base), state),
                )
            }
        }
    }

    fun cancelLogin() = _state.update { it.copy(haLogin = null) }

    /** Called by the login WebView for every navigation; returns true when it was the redirect. */
    fun onLoginNavigation(url: String): Boolean {
        val login = _state.value.haLogin ?: return false
        val callback = graph.haAuth.parseCallback(url) ?: return false
        when (callback) {
            is de.wuerl.easyconnect.ha.HaAuthClient.Callback.Error -> _state.update {
                it.copy(haLogin = login.copy(error = UiText.res(R.string.login_error_denied, callback.error)))
            }
            is de.wuerl.easyconnect.ha.HaAuthClient.Callback.Code -> {
                if (callback.state != login.state) {
                    _state.update { it.copy(haLogin = login.copy(error = UiText.res(R.string.login_error_state))) }
                } else {
                    _state.update { it.copy(haLogin = null, ha = HaUi.Connecting(login.baseUrl)) }
                    finishLogin(login.baseUrl, callback.code)
                }
            }
        }
        return true
    }

    fun onLoginPageError(message: UiText) = _state.update { it.copy(haLogin = it.haLogin?.copy(error = message)) }

    private fun finishLogin(baseUrl: String, code: String) {
        viewModelScope.launch {
            val base = baseUrl.toHttpUrl()
            try {
                val tokens = graph.haAuth.exchangeCode(base, code)
                val newSession = HaSession(
                    baseUrl = baseUrl,
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken ?: error("Home Assistant returned no refresh token"),
                    expiresAt = tokens.expiresAtMillis,
                )
                graph.store.saveLastHaUrl(baseUrl)
                attemptedFingerprint = null
                restoreSession(newSession)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(ha = HaUi.SignedOut, message = UiText.Raw(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private fun restoreSession(s: HaSession) {
        session = s
        graph.store.saveHa(s)
        val base = s.baseUrl.toHttpUrl()
        val tokens = HaTokenSource(graph.haAuth, base, HaTokens(s.accessToken, s.refreshToken, s.expiresAt)) { renewed ->
            session = session?.copy(
                accessToken = renewed.accessToken,
                refreshToken = renewed.refreshToken ?: s.refreshToken,
                expiresAt = renewed.expiresAtMillis,
            )
            session?.let(graph.store::saveHa)
        }
        api = HomeAssistantApi(graph.http, base, tokens)
        _state.update {
            it.copy(ha = HaUi.SignedIn(s.baseUrl, s.locationName, s.version, s.userName, s.isAdmin, IntegrationAvailability.Checking))
        }
        refreshHaStatus()
    }

    fun refreshHaStatus() {
        val client = api ?: return
        haStatusJob?.cancel()
        haStatusJob = viewModelScope.launch {
            updateHa { it.copy(integration = IntegrationAvailability.Checking) }
            try {
                val instance = client.instance()
                val user = client.currentUser()
                val handlers = client.flowHandlers()
                val target = IntegrationTarget.entries.firstOrNull { it.domain in handlers }
                session = session?.copy(userName = user.name, isAdmin = user.isAdmin, locationName = instance.locationName, version = instance.version)
                session?.let(graph.store::saveHa)
                updateHa {
                    it.copy(
                        locationName = instance.locationName,
                        version = instance.version,
                        userName = user.name,
                        isAdmin = user.isAdmin,
                        integration = target?.let(IntegrationAvailability::Installed) ?: IntegrationAvailability.Missing,
                    )
                }
                maybeAutoConfigure()
            } catch (e: HaException.SessionExpired) {
                dropSession()
                _state.update { it.copy(message = UiText.res(R.string.ha_session_expired)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is HaException.Unreachable) UiText.res(R.string.error_ha_unreachable) else UiText.Raw(e.message ?: e.javaClass.simpleName)
                updateHa { it.copy(integration = IntegrationAvailability.Unknown(message)) }
            }
        }
    }

    fun signOut() {
        val s = session
        dropSession()
        if (s != null) viewModelScope.launch { graph.haAuth.revoke(s.baseUrl.toHttpUrl(), s.refreshToken) }
    }

    private fun dropSession() {
        haStatusJob?.cancel()
        configureJob?.cancel()
        session = null
        api = null
        graph.store.saveHa(null)
        _state.update {
            it.copy(ha = HaUi.SignedOut, integration = if (it.integration is IntegrationUi.Done) it.integration else IntegrationUi.Waiting)
        }
    }

    private fun updateHa(transform: (HaUi.SignedIn) -> HaUi.SignedIn) = _state.update { s ->
        val ha = s.ha as? HaUi.SignedIn ?: return@update s
        s.copy(ha = transform(ha))
    }

    // --- The integration ------------------------------------------------------------------

    private fun installedTarget(): IntegrationTarget? =
        ((_state.value.ha as? HaUi.SignedIn)?.integration as? IntegrationAvailability.Installed)?.target

    private fun fingerprintFor(domain: String): String? {
        val u = unit ?: return null
        if (u.host == null) return null
        val s = session ?: return null
        return "$domain|${u.fingerprint}|${s.baseUrl}"
    }

    private fun refreshIntegrationCard() {
        val r = record
        val target = installedTarget()
        val domain = target?.domain ?: r?.domain ?: return
        val fp = fingerprintFor(domain)
        val current = _state.value.integration
        if (r != null && fp != null && r.fingerprint == fp) {
            if (current !is IntegrationUi.Running) _state.update { it.copy(integration = doneFromRecord(r)) }
        } else if (current is IntegrationUi.Done) {
            _state.update { it.copy(integration = IntegrationUi.Waiting) }
        }
    }

    private fun maybeAutoConfigure() {
        refreshIntegrationCard()
        val ha = _state.value.ha as? HaUi.SignedIn ?: return
        if (ha.isAdmin != true) return
        val target = installedTarget() ?: return
        val lan = (_state.value.unit as? UnitUi.Paired)?.lan
        if (lan !is LanUi.Found) return
        val fp = fingerprintFor(target.domain) ?: return
        if (record?.fingerprint == fp) return
        if (configureJob?.isActive == true || attemptedFingerprint == fp) return
        attemptedFingerprint = fp
        configure(target, fp)
    }

    /** "Try again" / "Run again" from the integration card. */
    fun runIntegrationAgain() {
        val target = installedTarget() ?: run { refreshHaStatus(); return }
        val fp = fingerprintFor(target.domain) ?: return
        attemptedFingerprint = fp
        configure(target, fp)
    }

    private fun configure(target: IntegrationTarget, fingerprint: String) {
        val client = api ?: return
        val u = unit ?: return
        val host = u.host ?: return
        configureJob?.cancel()
        configureJob = viewModelScope.launch {
            _state.update { it.copy(integration = IntegrationUi.Running(SetupStep.Starting)) }
            try {
                val outcome = IntegrationSetup(client).run(
                    target.domain,
                    UnitSetupData(host = host, port = u.port, deviceId = u.deviceId, key = u.key, name = u.bleName),
                ) { step -> _state.update { it.copy(integration = IntegrationUi.Running(step)) } }
                val (kind, title) = when (outcome) {
                    is SetupOutcome.Created -> IntegrationUi.Done.Kind.Created to outcome.title
                    is SetupOutcome.Updated -> IntegrationUi.Done.Kind.Updated to outcome.title
                    is SetupOutcome.AlreadyConfigured -> IntegrationUi.Done.Kind.AlreadyConfigured to outcome.title
                }
                record = IntegrationRecord(fingerprint, target.domain, title, kind.name, System.currentTimeMillis())
                graph.store.saveIntegration(record)
                _state.update { it.copy(integration = IntegrationUi.Done(kind, title, target)) }
            } catch (e: SetupFailedException) {
                _state.update { it.copy(integration = IntegrationUi.Failed(e.reason, setupError(e, host))) }
                if (e.reason == SetupFailedException.Reason.IntegrationMissing) refreshHaStatus()
            } catch (e: HaException.SessionExpired) {
                dropSession()
                _state.update { it.copy(message = UiText.res(R.string.ha_session_expired)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is HaException.Unreachable) UiText.res(R.string.error_ha_unreachable)
                else UiText.res(R.string.error_unexpected, e.message ?: e.javaClass.simpleName)
                _state.update { it.copy(integration = IntegrationUi.Failed(null, message)) }
            }
        }
    }

    private fun setupError(e: SetupFailedException, host: String): UiText = when (e.reason) {
        SetupFailedException.Reason.CannotConnect -> UiText.res(R.string.error_cannot_connect, host)
        SetupFailedException.Reason.InvalidKey -> UiText.res(R.string.error_invalid_key)
        SetupFailedException.Reason.NotAdmin -> UiText.res(R.string.error_not_admin)
        SetupFailedException.Reason.IntegrationMissing -> UiText.res(R.string.error_integration_missing)
        SetupFailedException.Reason.UnsupportedForm -> UiText.res(R.string.error_unsupported_form, e.detail.orEmpty())
        SetupFailedException.Reason.Aborted -> UiText.res(R.string.error_aborted, e.detail.orEmpty())
        SetupFailedException.Reason.Unexpected -> UiText.res(R.string.error_unexpected, e.detail.orEmpty())
    }

    private fun doneFromRecord(r: IntegrationRecord) = IntegrationUi.Done(
        kind = runCatching { IntegrationUi.Done.Kind.valueOf(r.outcome) }.getOrDefault(IntegrationUi.Done.Kind.Created),
        title = r.title,
        target = IntegrationTarget.forDomain(r.domain),
    )

    /** Deep link to the integration's page in Home Assistant. */
    fun integrationUrl(): String? {
        val s = session ?: return null
        val domain = installedTarget()?.domain ?: record?.domain ?: IntegrationTarget.E16.domain
        return "${s.baseUrl}/config/integrations/integration/$domain"
    }

    fun haUrl(): String? = session?.baseUrl

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun randomState(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private companion object {
        const val SCAN_TIMEOUT_MS = 60_000L
        const val AUTO_SELECT_AFTER_MS = 1_500L
        const val PAIRING_TIMEOUT_MS = 150_000L
        const val SUCCESS_LINGER_MS = 1_600L
        const val HA_DISCOVERY_SPINNER_MS = 6_000L
    }
}
