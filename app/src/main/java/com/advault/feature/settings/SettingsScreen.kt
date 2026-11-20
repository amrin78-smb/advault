package com.advault.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhonelinkErase
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.core.common.BlockResponse
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.core.common.UpdateFrequency
import com.advault.core.preferences.AppSettings
import com.advault.core.preferences.UpstreamPreset
import com.advault.core.preferences.UpstreamProtocol
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.CenteredLoading
import com.advault.feature.common.ChoiceDialog
import com.advault.feature.common.ConfirmDialog
import com.advault.feature.common.Format
import com.advault.feature.common.InfoBanner
import com.advault.feature.common.SectionHeader
import com.advault.feature.common.SettingsGroup
import com.advault.feature.common.SettingsRow
import com.advault.feature.common.StatusPill
import com.advault.feature.common.StatusTone
import com.advault.feature.common.SwitchRow
import com.advault.feature.common.SystemIntents
import com.advault.feature.common.WarningBanner
import com.advault.feature.common.resolve

private enum class SettingsDialog { Mode, Frequency, Resolver, Timeout, BlockResponse, Retention, Bytes, ClearActivity, ClearStats }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onOpenBlocklists: () -> Unit,
    onOpenExclusions: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it.resolve(context)) } }
    var batteryExempt by remember { mutableStateOf(SystemIntents.isIgnoringBatteryOptimisations(context)) }
    LifecycleResumeEffect(Unit) {
        batteryExempt = SystemIntents.isIgnoringBatteryOptimisations(context)
        onPauseOrDispose { }
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { AdVaultTopBar(stringResource(R.string.settings_title), scrollBehavior = scroll) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = state.settings
        if (s == null) {
            CenteredLoading(Modifier.padding(padding))
        } else {
            SettingsContent(
                s = s,
                batteryExempt = batteryExempt,
                viewModel = viewModel,
                onOpenBlocklists = onOpenBlocklists,
                onOpenExclusions = onOpenExclusions,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenAbout = onOpenAbout,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun SettingsContent(
    s: AppSettings,
    batteryExempt: Boolean,
    viewModel: SettingsViewModel,
    onOpenBlocklists: () -> Unit,
    onOpenExclusions: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        // ---------------------------------------------------------------- Protection
        SectionHeader(stringResource(R.string.settings_section_protection))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_mode),
                subtitle = s.defaultMode.displayName + " — " + stringResource(modeDescription(s.defaultMode)),
                icon = Icons.Outlined.Tune,
                onClick = { dialog = SettingsDialog.Mode },
            )
            SettingsRow(
                title = stringResource(R.string.settings_categories),
                subtitle = stringResource(R.string.settings_categories_value, s.enabledCategories.size, FilterCategory.entries.size),
                icon = Icons.Outlined.Category,
                onClick = onOpenBlocklists,
            )
            SettingsRow(
                title = stringResource(R.string.settings_exclusions),
                subtitle = stringResource(R.string.settings_exclusions_sub),
                icon = Icons.Outlined.PhonelinkErase,
                onClick = onOpenExclusions,
            )
        }

        // ---------------------------------------------------------------- Blocklists
        SectionHeader(stringResource(R.string.settings_section_blocklists))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_manage_blocklists),
                icon = Icons.Outlined.Security,
                onClick = onOpenBlocklists,
            )
            SettingsRow(
                title = stringResource(R.string.settings_update_frequency),
                subtitle = s.updateFrequency.displayName,
                icon = Icons.Outlined.Schedule,
                onClick = { dialog = SettingsDialog.Frequency },
            )
            SwitchRow(
                title = stringResource(R.string.settings_wifi_only),
                subtitle = stringResource(R.string.settings_wifi_only_sub),
                icon = Icons.Outlined.Wifi,
                checked = s.updateOnWifiOnly,
                onCheckedChange = viewModel::setWifiOnly,
                enabled = s.updateFrequency != UpdateFrequency.MANUAL,
            )
        }

        // ---------------------------------------------------------------- DNS
        SectionHeader(stringResource(R.string.settings_section_dns))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_resolver),
                subtitle = if (s.upstreamPreset == UpstreamPreset.CUSTOM) {
                    stringResource(R.string.settings_resolver_custom_value, s.customUpstreamProtocol.label(), s.customUpstream.ifBlank { "—" })
                } else s.upstreamPreset.displayName,
                icon = Icons.Outlined.Dns,
                onClick = { dialog = SettingsDialog.Resolver },
            )
            if (s.upstreamPreset == UpstreamPreset.CUSTOM) {
                CustomUpstreamEditor(s, viewModel)
            }
            SettingsRow(
                title = stringResource(R.string.settings_timeout),
                subtitle = stringResource(R.string.settings_timeout_value, s.dnsTimeoutMs / 1000.0),
                icon = Icons.Outlined.Timer,
                onClick = { dialog = SettingsDialog.Timeout },
            )
            SettingsRow(
                title = stringResource(R.string.settings_block_response),
                subtitle = s.blockResponse.displayName,
                icon = Icons.Outlined.Router,
                onClick = { dialog = SettingsDialog.BlockResponse },
            )
            SwitchRow(
                title = stringResource(R.string.settings_dns_cache),
                subtitle = stringResource(R.string.settings_dns_cache_sub),
                icon = Icons.Outlined.Cached,
                checked = s.dnsCacheEnabled,
                onCheckedChange = viewModel::setDnsCache,
            )
        }

        // ---------------------------------------------------------------- Startup & reliability
        SectionHeader(stringResource(R.string.settings_section_startup))
        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.settings_start_on_boot),
                subtitle = stringResource(R.string.settings_start_on_boot_sub),
                icon = Icons.Outlined.PowerSettingsNew,
                checked = s.startOnBoot,
                onCheckedChange = viewModel::setStartOnBoot,
            )
            SettingsRow(
                title = stringResource(R.string.settings_always_on),
                subtitle = stringResource(R.string.settings_always_on_sub),
                icon = Icons.Outlined.VpnLock,
                onClick = { SystemIntents.openVpnSettings(context) },
            )
            SettingsRow(
                title = stringResource(R.string.settings_battery),
                subtitle = stringResource(if (batteryExempt) R.string.settings_battery_ok else R.string.settings_battery_restricted),
                icon = if (batteryExempt) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryAlert,
                onClick = { SystemIntents.openBatteryOptimisationSettings(context) },
                trailing = {
                    StatusPill(
                        stringResource(if (batteryExempt) R.string.settings_battery_pill_ok else R.string.settings_battery_pill_restricted),
                        if (batteryExempt) StatusTone.Active else StatusTone.Warning,
                    )
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        if (SystemIntents.isSamsung) {
            WarningBanner(title = stringResource(R.string.settings_samsung_title), text = stringResource(R.string.settings_samsung_body))
        } else {
            InfoBanner(stringResource(R.string.settings_battery_tip))
        }

        // ---------------------------------------------------------------- Notifications
        SectionHeader(stringResource(R.string.settings_section_notifications))
        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.settings_notification_stats),
                subtitle = stringResource(R.string.settings_notification_stats_sub),
                icon = Icons.Outlined.NotificationsActive,
                checked = s.showStatsInNotification,
                onCheckedChange = viewModel::setStatsInNotification,
            )
            SettingsRow(
                title = stringResource(R.string.settings_notification_system),
                subtitle = stringResource(R.string.settings_notification_system_sub),
                icon = Icons.Outlined.Notifications,
                onClick = { SystemIntents.openAppNotificationSettings(context) },
            )
        }

        // ---------------------------------------------------------------- Data management
        SectionHeader(stringResource(R.string.settings_section_data))
        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.settings_activity_logging),
                subtitle = stringResource(R.string.settings_activity_logging_sub),
                icon = Icons.Outlined.History,
                checked = s.activityLoggingEnabled,
                onCheckedChange = viewModel::setActivityLogging,
            )
            SettingsRow(
                title = stringResource(R.string.settings_retention),
                subtitle = androidx.compose.ui.res.pluralStringResource(R.plurals.days, s.logRetentionDays, s.logRetentionDays),
                icon = Icons.Outlined.Schedule,
                onClick = { dialog = SettingsDialog.Retention },
            )
            SettingsRow(
                title = stringResource(R.string.settings_bytes_per_request),
                subtitle = stringResource(R.string.settings_bytes_per_request_sub, bytesLabel(s.estimatedBytesPerBlockedRequest)),
                icon = Icons.Outlined.DataSaverOn,
                onClick = { dialog = SettingsDialog.Bytes },
            )
            SettingsRow(
                title = stringResource(R.string.activity_clear),
                icon = Icons.Outlined.DeleteSweep,
                onClick = { dialog = SettingsDialog.ClearActivity },
                showChevron = false,
            )
            SettingsRow(
                title = stringResource(R.string.settings_clear_stats),
                icon = Icons.Outlined.QueryStats,
                onClick = { dialog = SettingsDialog.ClearStats },
                showChevron = false,
            )
        }

        // ---------------------------------------------------------------- Diagnostics & about
        SectionHeader(stringResource(R.string.settings_section_diagnostics))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.diagnostics_title),
                subtitle = stringResource(R.string.settings_diagnostics_sub),
                icon = Icons.Outlined.MonitorHeart,
                onClick = onOpenDiagnostics,
            )
            SwitchRow(
                title = stringResource(R.string.settings_diagnostic_logging),
                subtitle = stringResource(R.string.settings_diagnostic_logging_sub),
                icon = Icons.Outlined.BugReport,
                checked = s.diagnosticLogging,
                onCheckedChange = viewModel::setDiagnosticLogging,
            )
            SettingsRow(
                title = stringResource(R.string.about_title),
                icon = Icons.Outlined.Info,
                onClick = onOpenAbout,
            )
        }
        Spacer(Modifier.height(32.dp))
    }

    when (dialog) {
        SettingsDialog.Mode -> ChoiceDialog(
            title = stringResource(R.string.settings_mode),
            options = FilterMode.entries,
            selected = s.defaultMode,
            label = { it.displayName },
            description = { stringResource(modeDescription(it)) },
            onSelect = { viewModel.setDefaultMode(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Frequency -> ChoiceDialog(
            title = stringResource(R.string.settings_update_frequency),
            options = UpdateFrequency.entries,
            selected = s.updateFrequency,
            label = { it.displayName },
            onSelect = { viewModel.setUpdateFrequency(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Resolver -> ChoiceDialog(
            title = stringResource(R.string.settings_resolver),
            options = UpstreamPreset.entries,
            selected = s.upstreamPreset,
            label = { it.displayName },
            description = { p -> presetDescription(p) },
            onSelect = { viewModel.setUpstreamPreset(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Timeout -> ChoiceDialog(
            title = stringResource(R.string.settings_timeout),
            options = SettingsViewModel.TIMEOUT_OPTIONS,
            selected = s.dnsTimeoutMs,
            label = { String.format(java.util.Locale.getDefault(), "%.0f s", it / 1000.0) },
            onSelect = { viewModel.setDnsTimeout(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.BlockResponse -> ChoiceDialog(
            title = stringResource(R.string.settings_block_response),
            options = BlockResponse.entries,
            selected = s.blockResponse,
            label = { it.displayName },
            description = { stringResource(blockResponseDescription(it)) },
            onSelect = { viewModel.setBlockResponse(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Retention -> ChoiceDialog(
            title = stringResource(R.string.settings_retention),
            options = SettingsViewModel.RETENTION_OPTIONS,
            selected = s.logRetentionDays,
            label = { "$it d" },
            onSelect = { viewModel.setRetentionDays(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Bytes -> BytesDialog(s.estimatedBytesPerBlockedRequest, onSelect = { viewModel.setBytesPerBlocked(it); dialog = null }) { dialog = null }
        SettingsDialog.ClearActivity -> ConfirmDialog(
            title = stringResource(R.string.activity_clear_title),
            text = stringResource(R.string.activity_clear_body),
            confirmLabel = stringResource(R.string.action_clear),
            destructive = true,
            onConfirm = { viewModel.clearActivity(); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.ClearStats -> ConfirmDialog(
            title = stringResource(R.string.settings_clear_stats_title),
            text = stringResource(R.string.settings_clear_stats_body),
            confirmLabel = stringResource(R.string.action_clear),
            destructive = true,
            onConfirm = { viewModel.clearStatistics(); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private fun modeDescription(mode: FilterMode) = when (mode) {
    FilterMode.AGGRESSIVE -> R.string.settings_mode_aggressive_desc
    FilterMode.BALANCED -> R.string.settings_mode_balanced_desc
}

private fun blockResponseDescription(r: BlockResponse) = when (r) {
    BlockResponse.NULL_IP -> R.string.block_response_null_desc
    BlockResponse.NXDOMAIN -> R.string.block_response_nxdomain_desc
    BlockResponse.REFUSED -> R.string.block_response_refused_desc
}

private fun presetDescription(p: UpstreamPreset): String? = when {
    p == UpstreamPreset.CUSTOM -> null
    p.endpoint != null -> p.endpoint
    p.addresses.isNotEmpty() -> p.addresses.joinToString(", ")
    else -> null
}

private fun UpstreamProtocol.label(): String = when (this) {
    UpstreamProtocol.PLAIN -> "DNS"
    UpstreamProtocol.DOT -> "DoT"
    UpstreamProtocol.DOH -> "DoH"
}

private fun bytesLabel(v: Int): String = if (v == 0) "0 B" else Format.bytes(v.toLong())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomUpstreamEditor(s: AppSettings, viewModel: SettingsViewModel) {
    var protocol by rememberSaveable { mutableStateOf(s.customUpstreamProtocol) }
    var value by rememberSaveable { mutableStateOf(s.customUpstream) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UpstreamProtocol.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = protocol == p,
                    onClick = { protocol = p; error = null },
                    shape = SegmentedButtonDefaults.itemShape(i, UpstreamProtocol.entries.size),
                ) { Text(p.label()) }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.dns_custom_label)) },
            isError = error != null,
            supportingText = { Text(stringResource(error ?: UpstreamValidation.hint(protocol))) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { error = viewModel.saveCustomUpstream(protocol, value) }) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@Composable
private fun BytesDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog(
        title = stringResource(R.string.settings_bytes_per_request),
        options = SettingsViewModel.BYTES_OPTIONS,
        selected = current,
        label = { if (it == 0) stringResource(R.string.settings_bytes_zero) else Format.bytes(it.toLong()) },
        description = { if (it == SettingsViewModel.BYTES_OPTIONS[2]) stringResource(R.string.settings_bytes_default) else null },
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}
