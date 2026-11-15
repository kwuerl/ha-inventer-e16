// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import de.wuerl.easyconnect.ble.DeviceInformation
import de.wuerl.easyconnect.ble.FoundUnit
import de.wuerl.easyconnect.data.PairedUnit
import de.wuerl.easyconnect.device.LocateStage
import de.wuerl.easyconnect.ha.DiscoveredHa
import de.wuerl.easyconnect.ha.SetupFailedException
import de.wuerl.easyconnect.ha.SetupStep

/** Text that is either a string resource or a raw string (e.g. an error message). */
@Immutable
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> stringResource(id, *args.toTypedArray())
        is Raw -> text
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

/** The Home Assistant integrations this app knows how to set up, in order of preference. */
enum class IntegrationTarget(val domain: String, val displayName: String, val projectUrl: String) {
    E16("inventer_e16", "inVENTer Easy Connect e16", "https://github.com/tobiask1977/ha-inventer-e16"),
    ;

    companion object {
        fun forDomain(domain: String) = entries.firstOrNull { it.domain == domain }
    }
}

@Immutable
sealed interface LanUi {
    data class Searching(val stage: LocateStage?) : LanUi
    data class Found(val host: String, val port: Int, val zones: List<String>, val verified: Boolean) : LanUi

    /** Nothing accepted the key; [rejectedBy] answered but refused it. */
    data class NotFound(val rejectedBy: List<String>) : LanUi
    data object NoWifi : LanUi
}

@Immutable
sealed interface UnitUi {
    data object NotPaired : UnitUi
    data class Paired(val unit: PairedUnit, val lan: LanUi) : UnitUi
}

@Immutable
sealed interface IntegrationAvailability {
    data object Checking : IntegrationAvailability
    data class Installed(val target: IntegrationTarget) : IntegrationAvailability
    data object Missing : IntegrationAvailability
    data class Unknown(val message: UiText) : IntegrationAvailability
}

@Immutable
sealed interface HaUi {
    data object SignedOut : HaUi
    data class Connecting(val url: String) : HaUi
    data class SignedIn(
        val url: String,
        val locationName: String?,
        val version: String?,
        val userName: String?,
        val isAdmin: Boolean?,
        val integration: IntegrationAvailability,
    ) : HaUi
}

@Immutable
sealed interface IntegrationUi {
    data object Waiting : IntegrationUi
    data class Running(val step: SetupStep) : IntegrationUi
    data class Done(val kind: Kind, val title: String?, val target: IntegrationTarget?) : IntegrationUi {
        enum class Kind { Created, Updated, AlreadyConfigured }
    }
    data class Failed(val reason: SetupFailedException.Reason?, val message: UiText) : IntegrationUi
}

@Immutable
sealed interface PairingUi {
    data object NeedsPermission : PairingUi
    data object PermissionDenied : PairingUi
    data object BluetoothOff : PairingUi
    data object LocationOff : PairingUi
    data class Scanning(val found: List<FoundUnit>) : PairingUi
    data class Connecting(val unit: FoundUnit, val reconnecting: Boolean) : PairingUi
    data class WaitingForPairingMode(val unit: FoundUnit, val info: DeviceInformation, val deadline: Long) : PairingUi
    data class Reading(val unit: FoundUnit) : PairingUi
    data class Success(val deviceId: String) : PairingUi
    data class Failed(val message: UiText) : PairingUi
}

@Immutable
data class HaPickerUi(
    val discovered: List<DiscoveredHa> = emptyList(),
    val searching: Boolean = true,
    val input: String = "",
    val checking: Boolean = false,
    val error: UiText? = null,
)

@Immutable
data class HaLoginUi(val authorizeUrl: String, val baseUrl: String, val state: String, val error: UiText? = null)

@Immutable
data class ManualHostUi(
    val input: String,
    val checking: Boolean = false,
    val error: UiText? = null,
    /** The unit didn't answer from the phone; offer to hand the address to HA anyway. */
    val offerUseAnyway: Boolean = false,
)

@Immutable
data class SetupUiState(
    val unit: UnitUi = UnitUi.NotPaired,
    val ha: HaUi = HaUi.SignedOut,
    val integration: IntegrationUi = IntegrationUi.Waiting,
    val pairing: PairingUi? = null,
    val haPicker: HaPickerUi? = null,
    val haLogin: HaLoginUi? = null,
    val manualHost: ManualHostUi? = null,
    val showKey: Boolean = false,
    val confirmForget: Boolean = false,
    val message: UiText? = null,
)
