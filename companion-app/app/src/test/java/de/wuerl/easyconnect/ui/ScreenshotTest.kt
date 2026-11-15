// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import de.wuerl.easyconnect.ble.DeviceInformation
import de.wuerl.easyconnect.ble.FoundUnit
import de.wuerl.easyconnect.data.PairedUnit
import de.wuerl.easyconnect.device.LocateStage
import de.wuerl.easyconnect.ha.DiscoveredHa
import de.wuerl.easyconnect.ha.SetupFailedException
import de.wuerl.easyconnect.ha.SetupStep
import de.wuerl.easyconnect.ui.theme.EasyConnectTheme
import de.wuerl.easyconnect.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main states of the app to PNGs (app/build/outputs/roborazzi) for design review.
 * Run with `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h1180dp-xxhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val unit = PairedUnit(
        bleAddress = "00:00:5E:00:53:01", bleName = "Easy Connect e16", deviceId = "ABCDE-FGHIJ", key = key,
        model = "50", serial = "0000000", firmware = "5.30.10", pairedAt = 0L,
        host = "192.0.2.10", hostVerified = true, zones = listOf("Zone 1", "Bedroom", "Hallway"),
    )
    private val haDone = HaUi.SignedIn(
        url = "http://homeassistant.local:8123", locationName = "Home", version = "2026.11.1", userName = "Alex", isAdmin = true,
        integration = IntegrationAvailability.Installed(IntegrationTarget.E16),
    )

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EasyConnectTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(700)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun setup_fresh() = shot("01_setup_fresh") { SetupScreen(SetupUiState(), SetupActions()) }

    @Test
    fun setup_in_progress() = shot("02_setup_in_progress") {
        SetupScreen(
            SetupUiState(
                unit = UnitUi.Paired(unit.copy(host = null), LanUi.Searching(LocateStage.Scanning("192.0.2.0/24"))),
                ha = HaUi.Connecting("http://homeassistant.local:8123"),
            ),
            SetupActions(),
        )
    }

    @Test
    fun setup_running() = shot("03_setup_running") {
        SetupScreen(
            SetupUiState(
                unit = UnitUi.Paired(unit, LanUi.Found("192.0.2.10", 47820, unit.zones, verified = true)),
                ha = haDone,
                integration = IntegrationUi.Running(SetupStep.Validating),
            ),
            SetupActions(),
        )
    }

    @Test
    fun setup_done() = shot("04_setup_done") { SetupScreen(doneState(), SetupActions()) }

    @Test
    fun setup_done_dark() = shot("05_setup_done_dark", dark = true) { SetupScreen(doneState(), SetupActions()) }

    @Test
    @Config(qualifiers = "de-w412dp-h1180dp-xxhdpi")
    fun setup_done_german() = shot("06_setup_done_de") { SetupScreen(doneState(), SetupActions()) }

    @Test
    fun setup_problems() = shot("07_setup_problems") {
        SetupScreen(
            SetupUiState(
                unit = UnitUi.Paired(unit.copy(host = null), LanUi.NotFound(emptyList())),
                ha = haDone.copy(integration = IntegrationAvailability.Missing),
                integration = IntegrationUi.Failed(
                    SetupFailedException.Reason.CannotConnect,
                    UiText.res(R.string.error_cannot_connect, "192.0.2.10"),
                ),
            ),
            SetupActions(),
        )
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun pairing_waiting() = shot("08_pairing_waiting") {
        PairingContent(
            PairingUi.WaitingForPairingMode(
                FoundUnit("00:00:5E:00:53:01", "Easy Connect e16", -58),
                DeviceInformation(model = "50", firmware = "5.30.10"),
                deadline = System.currentTimeMillis() + 134_000,
            ),
            PairingActions(),
        )
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun pairing_pick() = shot("09_pairing_pick") {
        PairingContent(
            PairingUi.Scanning(
                listOf(
                    FoundUnit("00:00:5E:00:53:01", "Easy Connect e16", -52),
                    FoundUnit("00:00:5E:00:53:02", "Easy Connect e16", -84),
                ),
            ),
            PairingActions(),
        )
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun pairing_success_dark() = shot("10_pairing_success_dark", dark = true) {
        PairingContent(PairingUi.Success("ABCDE-FGHIJ"), PairingActions())
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun ha_picker() = shot("11_ha_picker") {
        HaPickerContent(
            HaPickerUi(
                discovered = listOf(DiscoveredHa("Home", "http://homeassistant.local:8123", "2026.11.1")),
                searching = false,
                input = "http://homeassistant.local:8123",
            ),
            HaPickerActions(),
        )
    }

    @Test
    fun local_network_denied() = shot("12_local_network_denied") {
        SetupScreen(
            SetupUiState(
                unit = UnitUi.Paired(unit.copy(host = null), LanUi.Blocked),
                ha = haDone.copy(integration = IntegrationAvailability.Unknown(UiText.res(R.string.local_network_off_ha))),
                localNetwork = LocalNetworkUi.Denied(canAskAgain = true),
            ),
            SetupActions(),
        )
    }

    private fun doneState() = SetupUiState(
        unit = UnitUi.Paired(unit, LanUi.Found("192.0.2.10", 47820, unit.zones, verified = true)),
        ha = haDone,
        integration = IntegrationUi.Done(IntegrationUi.Done.Kind.Created, "inVENTer Zone 1", IntegrationTarget.E16),
    )
}
