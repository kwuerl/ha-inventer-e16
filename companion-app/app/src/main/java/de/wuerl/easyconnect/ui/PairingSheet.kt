// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.wuerl.easyconnect.R
import de.wuerl.easyconnect.ble.FoundUnit
import de.wuerl.easyconnect.ui.components.ControllerIllustration
import de.wuerl.easyconnect.ui.components.LedMode
import de.wuerl.easyconnect.ui.components.StatusLine
import de.wuerl.easyconnect.ui.theme.AppTheme
import kotlinx.coroutines.delay

class PairingActions(
    val onAllow: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onEnableBluetooth: () -> Unit = {},
    val onEnableLocation: () -> Unit = {},
    val onSelect: (FoundUnit) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingSheet(pairing: PairingUi, actions: PairingActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        PairingContent(pairing, actions)
    }
}

/** The sheet body, separate from the sheet so it can be previewed and screenshot-tested. */
@Composable
fun PairingContent(pairing: PairingUi, actions: PairingActions) {
    val ledMode = when (pairing) {
        is PairingUi.WaitingForPairingMode -> LedMode.BlinkBlue
        is PairingUi.Reading -> LedMode.BlinkBlue
        is PairingUi.Success -> LedMode.Success
        is PairingUi.Failed, PairingUi.PermissionDenied -> LedMode.Error
        is PairingUi.Scanning, is PairingUi.Connecting -> LedMode.Normal
        else -> LedMode.Idle
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ControllerIllustration(ledMode, stringResource(R.string.led_label_mode_button))
        }
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tip(1, stringResource(R.string.pairing_tip_app))
            Tip(2, stringResource(R.string.pairing_tip_button), highlight = pairing is PairingUi.WaitingForPairingMode)
            Tip(3, stringResource(R.string.pairing_tip_wait))
        }
        Spacer(Modifier.size(20.dp))
        HorizontalDivider()
        Spacer(Modifier.size(16.dp))
        AnimatedContent(
            targetState = pairing,
            contentKey = { it::class },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "pairing-status",
        ) { state ->
            Column(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PairingStatus(state, actions)
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = actions.onClose) {
                Text(stringResource(if (pairing is PairingUi.Success) R.string.action_close else R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun PairingStatus(state: PairingUi, actions: PairingActions) {
    val colors = MaterialTheme.colorScheme
    when (state) {
        PairingUi.NeedsPermission -> {
            StatusLine(stringResource(R.string.pairing_status_permission), icon = Icons.Rounded.Lock)
            Button(onClick = actions.onAllow) { Text(stringResource(R.string.action_allow)) }
        }
        PairingUi.PermissionDenied -> {
            StatusLine(stringResource(R.string.pairing_status_denied), icon = Icons.Rounded.Lock, iconTint = colors.error)
            Button(onClick = actions.onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
        }
        PairingUi.BluetoothOff -> {
            StatusLine(stringResource(R.string.pairing_status_bt_off), icon = Icons.Rounded.BluetoothDisabled, iconTint = colors.error)
            Button(onClick = actions.onEnableBluetooth) { Text(stringResource(R.string.action_turn_on_bluetooth)) }
        }
        PairingUi.LocationOff -> {
            StatusLine(stringResource(R.string.pairing_status_location_off), icon = Icons.Rounded.LocationOff, iconTint = colors.error)
            Button(onClick = actions.onEnableLocation) { Text(stringResource(R.string.action_turn_on_location)) }
        }
        is PairingUi.Scanning -> {
            if (state.found.size > 1) {
                Text(stringResource(R.string.pairing_status_pick), style = MaterialTheme.typography.bodyMedium)
                state.found.forEach { unit -> FoundUnitRow(unit) { actions.onSelect(unit) } }
            } else {
                StatusLine(stringResource(R.string.pairing_status_scanning), working = true)
                state.found.singleOrNull()?.let { unit -> FoundUnitRow(unit) { actions.onSelect(unit) } }
            }
        }
        is PairingUi.Connecting -> StatusLine(
            if (state.reconnecting) stringResource(R.string.pairing_status_reconnecting)
            else stringResource(R.string.pairing_status_connecting, state.unit.name),
            working = true,
        )
        is PairingUi.WaitingForPairingMode -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(state.deadline) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            val left = ((state.deadline - now) / 1000).coerceAtLeast(0)
            StatusLine(
                stringResource(R.string.pairing_status_waiting),
                icon = Icons.Rounded.Bluetooth,
                iconTint = AppTheme.extended.pairingBlue,
                secondary = stringResource(R.string.pairing_time_left, "%d:%02d".format(left / 60, left % 60)),
            )
        }
        is PairingUi.Reading -> StatusLine(stringResource(R.string.pairing_status_reading), working = true)
        is PairingUi.Success -> StatusLine(
            stringResource(R.string.pairing_status_success, state.deviceId),
            icon = Icons.Rounded.CheckCircle,
            iconTint = AppTheme.extended.success,
        )
        is PairingUi.Failed -> {
            StatusLine(state.message.resolve(), icon = Icons.Rounded.ErrorOutline, iconTint = colors.error)
            Button(onClick = actions.onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun Tip(number: Int, text: String, highlight: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = if (highlight) AppTheme.extended.pairingBlue else colors.secondaryContainer,
            contentColor = if (highlight) colors.surface else colors.onSecondaryContainer,
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.labelMedium) }
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun FoundUnitRow(unit: FoundUnit, onClick: () -> Unit) {
    val signal = when {
        unit.rssi >= -65 -> R.string.pairing_signal_strong
        unit.rssi >= -80 -> R.string.pairing_signal_good
        else -> R.string.pairing_signal_weak
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(unit.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${stringResource(signal)} · ${unit.address}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
