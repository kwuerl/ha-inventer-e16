// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.wuerl.easyconnect.BuildConfig
import de.wuerl.easyconnect.R
import de.wuerl.easyconnect.device.LocateStage
import de.wuerl.easyconnect.ha.SetupFailedException
import de.wuerl.easyconnect.ha.SetupStep
import de.wuerl.easyconnect.ui.components.StatusLine
import de.wuerl.easyconnect.ui.components.StepCard
import de.wuerl.easyconnect.ui.components.StepItem
import de.wuerl.easyconnect.ui.components.StepStatus
import de.wuerl.easyconnect.ui.theme.AppTheme

/** Callbacks of the setup screen; kept in one place so the screen stays stateless. */
class SetupActions(
    val onPair: () -> Unit = {},
    val onSearchAgain: () -> Unit = {},
    val onEnterAddress: () -> Unit = {},
    val onShowKey: () -> Unit = {},
    val onForget: () -> Unit = {},
    val onConnectHa: () -> Unit = {},
    val onCheckIntegration: () -> Unit = {},
    val onGetIntegration: () -> Unit = {},
    val onOpenHa: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onOpenIntegration: () -> Unit = {},
    val onRunAgain: () -> Unit = {},
)

@Composable
fun SetupScreen(state: SetupUiState, actions: SetupActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Header()
                Spacer(Modifier.height(28.dp))
                StepItem(1, unitStatus(state.unit), isLast = false) { UnitCard(state.unit, actions) }
                StepItem(2, haStatus(state.ha), isLast = false) { HaCard(state.ha, actions) }
                StepItem(3, integrationStatus(state.integration), isLast = true) { IntegrationCard(state, actions) }
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(R.string.footer_unofficial) + "\n" +
                        stringResource(R.string.footer_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AirflowMark(Modifier.size(28.dp))
            Spacer(Modifier.size(10.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The app's mark: the launcher icon's airflow strokes, drawn in the theme colours. */
@Composable
fun AirflowMark(modifier: Modifier = Modifier) {
    val main = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val s = size.width / 72f // artwork drawn on a 72-unit grid (the icon's safe zone)
        val stroke = Stroke(width = 6f * s, cap = StrokeCap.Round)
        fun curl(x0: Float, y: Float, x1: Float, up: Boolean) = Path().apply {
            moveTo(x0 * s, y * s)
            lineTo(x1 * s, y * s)
            val cy = if (up) y - 8f else y + 8f
            arcTo(
                androidx.compose.ui.geometry.Rect(Offset((x1 - 8f) * s, (cy - 8f) * s), androidx.compose.ui.geometry.Size(16f * s, 16f * s)),
                startAngleDegrees = if (up) 90f else -90f,
                sweepAngleDegrees = if (up) -270f else 270f,
                forceMoveTo = false,
            )
        }
        drawPath(curl(13f, 23f, 43f, up = true), main, style = stroke)
        drawLine(accent, Offset(8f * s, 36f * s), Offset(64f * s, 36f * s), strokeWidth = 6f * s, cap = StrokeCap.Round)
        drawPath(curl(19f, 49f, 47f, up = false), main, style = stroke)
    }
}

// --- Step 1 -------------------------------------------------------------------------------

private fun unitStatus(unit: UnitUi) = when (unit) {
    UnitUi.NotPaired -> StepStatus.Active
    is UnitUi.Paired -> when (unit.lan) {
        is LanUi.Searching -> StepStatus.Working
        is LanUi.Found -> StepStatus.Done
        else -> StepStatus.Error
    }
}

@Composable
private fun UnitCard(unit: UnitUi, actions: SetupActions) {
    StepCard(
        icon = Icons.Rounded.Bluetooth,
        title = stringResource(R.string.step_unit_title),
        modifier = Modifier.animateContentSize(),
        trailing = if (unit is UnitUi.Paired) {
            { UnitMenu(actions) }
        } else {
            null
        },
    ) {
        when (unit) {
            UnitUi.NotPaired -> {
                BodyText(stringResource(R.string.step_unit_todo))
                Button(onClick = actions.onPair) {
                    Icon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_pair))
                }
            }
            is UnitUi.Paired -> {
                val u = unit.unit
                val details = listOfNotNull(
                    u.firmware?.let { stringResource(R.string.unit_firmware, it) },
                    u.serial?.let { stringResource(R.string.unit_serial, it) },
                ).joinToString(" · ")
                StatusLine(
                    text = stringResource(R.string.unit_device_id, u.deviceId),
                    icon = Icons.Rounded.Key,
                    iconTint = MaterialTheme.colorScheme.primary,
                    secondary = details.ifBlank { null },
                )
                LanStatus(unit.lan, actions)
            }
        }
    }
}

@Composable
private fun LanStatus(lan: LanUi, actions: SetupActions) {
    AnimatedContent(targetState = lan, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lan") { state ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is LanUi.Searching -> StatusLine(
                    text = stringResource(R.string.lan_searching),
                    working = true,
                    secondary = when (val stage = state.stage) {
                        LocateStage.Discovering -> stringResource(R.string.lan_stage_discovering)
                        is LocateStage.Scanning -> stringResource(R.string.lan_stage_scanning, stage.subnet)
                        is LocateStage.Checking -> stringResource(R.string.lan_stage_checking, stage.host)
                        null -> null
                    },
                )
                is LanUi.Found -> {
                    StatusLine(
                        text = if (state.verified) stringResource(R.string.lan_found, state.host)
                        else stringResource(R.string.lan_found_unverified, state.host),
                        icon = Icons.Rounded.Wifi,
                        iconTint = if (state.verified) AppTheme.extended.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.zones.isNotEmpty()) ZoneChips(state.zones)
                }
                is LanUi.NotFound -> {
                    StatusLine(
                        text = if (state.rejectedBy.isNotEmpty()) stringResource(R.string.lan_rejected, state.rejectedBy.joinToString())
                        else stringResource(R.string.lan_not_found),
                        icon = Icons.Rounded.WifiOff,
                        iconTint = MaterialTheme.colorScheme.error,
                    )
                    RetryRow(actions)
                }
                LanUi.NoWifi -> {
                    StatusLine(text = stringResource(R.string.lan_no_wifi), icon = Icons.Rounded.WifiOff, iconTint = MaterialTheme.colorScheme.error)
                    RetryRow(actions)
                }
            }
        }
    }
}

@Composable
private fun RetryRow(actions: SetupActions) {
    ActionRow {
        FilledTonalButton(onClick = actions.onSearchAgain) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_search_again))
        }
        TextButton(onClick = actions.onEnterAddress) { Text(stringResource(R.string.action_enter_address)) }
    }
}

@Composable
private fun ZoneChips(zones: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 30.dp)) {
        zones.forEach { zone ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    zone,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun UnitMenu(actions: SetupActions) {
    OverflowMenu(
        listOf(
            MenuEntry(stringResource(R.string.menu_show_key), Icons.Rounded.Key, actions.onShowKey),
            MenuEntry(stringResource(R.string.menu_change_address), Icons.Rounded.Router, actions.onEnterAddress),
            MenuEntry(stringResource(R.string.menu_pair_again), Icons.Rounded.Bluetooth, actions.onPair),
            MenuEntry(stringResource(R.string.menu_forget_unit), Icons.Rounded.LinkOff, actions.onForget),
        ),
    )
}

// --- Step 2 -------------------------------------------------------------------------------

private fun haStatus(ha: HaUi) = when (ha) {
    HaUi.SignedOut -> StepStatus.Active
    is HaUi.Connecting -> StepStatus.Working
    is HaUi.SignedIn -> when {
        ha.isAdmin == false -> StepStatus.Error
        ha.integration is IntegrationAvailability.Checking -> StepStatus.Working
        ha.integration is IntegrationAvailability.Installed -> StepStatus.Done
        else -> StepStatus.Error
    }
}

@Composable
private fun HaCard(ha: HaUi, actions: SetupActions) {
    StepCard(
        icon = Icons.Rounded.Home,
        title = (ha as? HaUi.SignedIn)?.locationName ?: stringResource(R.string.step_ha_title),
        modifier = Modifier.animateContentSize(),
        trailing = if (ha is HaUi.SignedIn) {
            {
                OverflowMenu(
                    listOf(
                        MenuEntry(stringResource(R.string.menu_open_ha), Icons.AutoMirrored.Rounded.OpenInNew, actions.onOpenHa),
                        MenuEntry(stringResource(R.string.menu_sign_out), Icons.Rounded.LinkOff, actions.onSignOut),
                    ),
                )
            }
        } else {
            null
        },
    ) {
        when (ha) {
            HaUi.SignedOut -> {
                BodyText(stringResource(R.string.step_ha_todo))
                Button(onClick = actions.onConnectHa) {
                    Icon(Icons.Rounded.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_connect))
                }
            }
            is HaUi.Connecting -> StatusLine(stringResource(R.string.ha_connecting, ha.url), working = true)
            is HaUi.SignedIn -> {
                val user = ha.userName ?: stringResource(R.string.ha_user_unknown)
                StatusLine(
                    text = if (ha.isAdmin == false) stringResource(R.string.ha_not_admin, user)
                    else stringResource(R.string.ha_signed_in_as, user) + if (ha.isAdmin == true) " · " + stringResource(R.string.ha_admin) else "",
                    icon = if (ha.isAdmin == false) Icons.Rounded.WarningAmber else Icons.Rounded.Person,
                    iconTint = if (ha.isAdmin == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    secondary = listOfNotNull(ha.version?.let { stringResource(R.string.ha_version, it) }, ha.url).joinToString(" · "),
                )
                when (val integration = ha.integration) {
                    IntegrationAvailability.Checking -> StatusLine(stringResource(R.string.ha_integration_checking), working = true)
                    is IntegrationAvailability.Installed -> StatusLine(
                        stringResource(R.string.ha_integration_installed, integration.target.displayName),
                        icon = Icons.Rounded.CheckCircle,
                        iconTint = AppTheme.extended.success,
                    )
                    IntegrationAvailability.Missing -> {
                        StatusLine(stringResource(R.string.ha_integration_missing), icon = Icons.Rounded.WarningAmber, iconTint = MaterialTheme.colorScheme.error)
                        ActionRow {
                            FilledTonalButton(onClick = actions.onGetIntegration) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.action_get_integration))
                            }
                            TextButton(onClick = actions.onCheckIntegration) { Text(stringResource(R.string.action_check_again)) }
                        }
                    }
                    is IntegrationAvailability.Unknown -> {
                        StatusLine(
                            stringResource(R.string.ha_integration_unknown, integration.message.resolve()),
                            icon = Icons.Rounded.WarningAmber,
                            iconTint = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = actions.onCheckIntegration) { Text(stringResource(R.string.action_check_again)) }
                    }
                }
            }
        }
    }
}

// --- Step 3 -------------------------------------------------------------------------------

private fun integrationStatus(integration: IntegrationUi) = when (integration) {
    IntegrationUi.Waiting -> StepStatus.Todo
    is IntegrationUi.Running -> StepStatus.Working
    is IntegrationUi.Done -> StepStatus.Done
    is IntegrationUi.Failed -> StepStatus.Error
}

@Composable
private fun IntegrationCard(state: SetupUiState, actions: SetupActions) {
    val integration = state.integration
    StepCard(
        icon = Icons.Rounded.Hub,
        title = stringResource(R.string.step_integration_title),
        modifier = Modifier.animateContentSize(),
    ) {
        when (integration) {
            IntegrationUi.Waiting -> {
                BodyText(stringResource(R.string.integration_waiting))
                Prerequisites(state)
            }
            is IntegrationUi.Running -> {
                StatusLine(
                    when (integration.step) {
                        SetupStep.Starting -> stringResource(R.string.integration_step_starting)
                        SetupStep.Validating -> stringResource(R.string.integration_step_validating)
                        SetupStep.UpdatingExisting -> stringResource(R.string.integration_step_updating)
                    },
                    icon = Icons.Rounded.Hub,
                    iconTint = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 30.dp))
            }
            is IntegrationUi.Done -> {
                val title = integration.title
                StatusLine(
                    text = when (integration.kind) {
                        IntegrationUi.Done.Kind.Created -> stringResource(R.string.integration_done_created, title ?: "inVENTer e16")
                        IntegrationUi.Done.Kind.Updated ->
                            if (title != null) stringResource(R.string.integration_done_updated, title)
                            else stringResource(R.string.integration_done_updated_untitled)
                        IntegrationUi.Done.Kind.AlreadyConfigured -> stringResource(R.string.integration_done_already)
                    },
                    icon = Icons.Rounded.CheckCircle,
                    iconTint = AppTheme.extended.success,
                    secondary = stringResource(R.string.integration_done_hint),
                )
                ActionRow {
                    Button(onClick = actions.onOpenIntegration, enabled = state.ha is HaUi.SignedIn) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.action_open_integration))
                    }
                    TextButton(onClick = actions.onRunAgain, enabled = isReady(state)) { Text(stringResource(R.string.action_run_again)) }
                }
            }
            is IntegrationUi.Failed -> {
                StatusLine(integration.message.resolve(), icon = Icons.Rounded.ErrorOutline, iconTint = MaterialTheme.colorScheme.error)
                if (isReady(state)) {
                    ActionRow {
                        Button(onClick = actions.onRunAgain) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.action_retry))
                        }
                        if (integration.reason == SetupFailedException.Reason.InvalidKey) {
                            TextButton(onClick = actions.onPair) { Text(stringResource(R.string.menu_pair_again)) }
                        }
                    }
                } else {
                    Prerequisites(state)
                }
            }
        }
    }
}

private fun isReady(state: SetupUiState): Boolean {
    val lan = (state.unit as? UnitUi.Paired)?.lan
    val ha = state.ha as? HaUi.SignedIn
    return lan is LanUi.Found && ha != null && ha.isAdmin == true && ha.integration is IntegrationAvailability.Installed
}

/** The list of things still missing before the integration can be set up. */
@Composable
private fun Prerequisites(state: SetupUiState) {
    val ha = state.ha as? HaUi.SignedIn
    val items = listOf(
        stringResource(R.string.integration_needs_unit) to (state.unit is UnitUi.Paired),
        stringResource(R.string.integration_needs_address) to ((state.unit as? UnitUi.Paired)?.lan is LanUi.Found),
        stringResource(R.string.integration_needs_ha) to (ha != null),
        stringResource(R.string.integration_needs_admin) to (ha?.isAdmin == true),
        stringResource(R.string.integration_needs_install) to (ha?.integration is IntegrationAvailability.Installed),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, done) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (done) AppTheme.extended.success else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// --- shared bits ----------------------------------------------------------------------------

/** Buttons that wrap onto a second line instead of squeezing (long German labels). */
@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

class MenuEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun OverflowMenu(entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = { Icon(entry.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

@Composable
fun MonoText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), modifier = modifier)
}

val SheetPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
