package com.advault.feature.dashboard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.data.models.DailyPoint
import com.advault.feature.common.AdVaultCard
import com.advault.feature.common.AdVaultTheme
import com.advault.feature.common.ErrorBanner
import com.advault.feature.common.Format
import com.advault.feature.common.InfoBanner
import com.advault.feature.common.SectionHeader
import com.advault.feature.common.StatCard
import com.advault.feature.common.StatusPill
import com.advault.feature.common.StatusTone
import com.advault.feature.common.SystemIntents
import com.advault.feature.common.WarningBanner
import com.advault.feature.common.colors
import com.advault.feature.common.resolve

@Composable
fun DashboardRoute(
    onOpenActivity: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenBlocklists: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var notificationAsked by rememberSaveable { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshDiagnostics()
        onPauseOrDispose { }
    }

    LaunchedEffect(viewModel) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is DashboardEffect.LaunchVpnConsent -> runCatching { consentLauncher.launch(effect.intent) }
                    .onFailure { viewModel.onVpnConsentResult(false) }
                DashboardEffect.RequestNotificationPermission -> {
                    notificationAsked = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else viewModel.onNotificationPermissionResult(true)
                }
                is DashboardEffect.ShowMessage -> snackbar.showSnackbar(effect.text.resolve(context))
            }
        }
    }

    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        DashboardContent(
            state = state,
            onToggle = { viewModel.onToggle(it, needsNotificationPermission()) },
            onOpenActivity = onOpenActivity,
            onOpenApps = onOpenApps,
            onOpenBlocklists = onOpenBlocklists,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenPrivateDnsSettings = { SystemIntents.openPrivateDnsSettings(context) },
            onEstimateInfo = viewModel::showEstimateExplanation,
            modifier = Modifier.padding(padding),
        )
    }

    when (val dialog = state.dialog) {
        is DashboardDialog.ConfirmOtherVpn -> OtherVpnDialog(
            alwaysOnOther = dialog.alwaysOnOther,
            onConfirm = viewModel::onOtherVpnConfirmed,
            onDismiss = viewModel::dismissDialog,
            onOpenVpnSettings = { SystemIntents.openVpnSettings(context) },
        )
        DashboardDialog.EstimateExplanation -> EstimateDialog(state.bytesPerBlockedRequest, viewModel::dismissDialog)
        null -> Unit
    }
}

/** Stateless dashboard body (also used by UI tests). */
@Composable
fun DashboardContent(
    state: DashboardUiState,
    onToggle: (Boolean) -> Unit,
    onOpenActivity: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenBlocklists: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenPrivateDnsSettings: () -> Unit,
    onEstimateInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Header(onOpenDiagnostics)
        ProtectionHero(state.protection, onToggle)

        if (state.privateDnsActive) {
            Spacer(Modifier.height(12.dp))
            WarningBanner(
                title = stringResource(R.string.dashboard_private_dns_title),
                text = if (state.privateDnsServer != null) {
                    stringResource(R.string.dashboard_private_dns_body_server, state.privateDnsServer)
                } else stringResource(R.string.dashboard_private_dns_body),
                action = {
                    TextButton(onClick = onOpenPrivateDnsSettings) { Text(stringResource(R.string.dashboard_private_dns_action)) }
                },
            )
        }
        when (state.protection.kind) {
            StatusKind.Error -> {
                Spacer(Modifier.height(12.dp))
                ErrorBanner(
                    title = stringResource(R.string.status_error_title),
                    text = (state.protection.message ?: "") + "\n" + stringResource(
                        if (state.protection.recoverable) R.string.status_error_recoverable else R.string.status_error_unrecoverable,
                    ),
                    action = { TextButton(onClick = onOpenDiagnostics) { Text(stringResource(R.string.action_open_diagnostics)) } },
                )
            }
            StatusKind.Revoked -> {
                Spacer(Modifier.height(12.dp))
                WarningBanner(
                    title = stringResource(R.string.status_revoked_title),
                    text = listOfNotNull(state.protection.message, stringResource(R.string.status_revoked_explanation)).joinToString("\n"),
                )
            }
            else -> Unit
        }

        SectionHeader(stringResource(R.string.dashboard_section_stats))
        if (!state.hasTraffic) {
            InfoBanner(stringResource(R.string.dashboard_no_traffic))
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = stringResource(R.string.stat_ads_blocked),
                value = state.adsBlocked.value,
                icon = Icons.Outlined.Block,
                tone = StatusTone.Info,
                modifier = Modifier.weight(1f).testTag("stat_ads"),
            )
            StatCard(
                label = stringResource(R.string.stat_tracking_blocked),
                value = state.trackingBlocked.value,
                icon = Icons.Outlined.TrackChanges,
                tone = StatusTone.Info,
                modifier = Modifier.weight(1f).testTag("stat_tracking"),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = stringResource(R.string.stat_data_saved),
                value = state.estimatedDataSaved.value,
                caption = if (state.estimatedDataSaved.isEstimate) stringResource(R.string.stat_estimate_tag) else null,
                icon = Icons.Outlined.DataSaverOn,
                tone = StatusTone.Warning,
                onClick = onEstimateInfo,
                trailing = {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.stat_estimate_info_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.weight(1f).testTag("stat_estimate"),
            )
            StatCard(
                label = stringResource(R.string.stat_protected_apps),
                value = state.protectedApps.value,
                icon = Icons.Outlined.PhoneAndroid,
                tone = StatusTone.Active,
                onClick = onOpenApps,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        AdVaultCard(Modifier.fillMaxWidth()) {
            SummaryLine(stringResource(R.string.stat_total_queries), state.totalQueries.value)
            SummaryLine(stringResource(R.string.stat_blocked_queries), state.blockedQueries.value)
            SummaryLine(
                stringResource(R.string.stat_last_blocklist_update),
                Format.relativeTime(state.lastBlocklistUpdate) ?: stringResource(R.string.common_never),
            )
        }

        SectionHeader(stringResource(R.string.dashboard_section_week))
        WeekChartCard(state.daily)

        SectionHeader(stringResource(R.string.dashboard_section_quick))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(stringResource(R.string.dashboard_action_activity), Icons.AutoMirrored.Outlined.ListAlt, onOpenActivity)
            QuickAction(stringResource(R.string.dashboard_action_apps), Icons.Outlined.Apps, onOpenApps)
            QuickAction(stringResource(R.string.dashboard_action_blocklists), Icons.Outlined.Dns, onOpenBlocklists)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header(onOpenDiagnostics: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.app_name).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenDiagnostics) {
            Icon(Icons.Outlined.MonitorHeart, contentDescription = stringResource(R.string.action_open_diagnostics))
        }
    }
}

@Composable
private fun ProtectionHero(p: ProtectionUi, onToggle: (Boolean) -> Unit) {
    val tone = p.tone.colors()
    val ringColor by animateColorAsState(tone.content, tween(400), label = "ring")
    val (title, detail) = statusTexts(p)
    val icon: ImageVector = when (p.kind) {
        StatusKind.Running -> Icons.Filled.GppGood
        StatusKind.Error, StatusKind.Revoked -> Icons.Filled.GppBad
        StatusKind.Starting, StatusKind.WaitingForNetwork -> Icons.Filled.GppMaybe
        StatusKind.Stopped -> Icons.Filled.Shield
    }
    val toggleLabel = stringResource(R.string.dashboard_toggle_label)
    val stateLabel = stringResource(if (p.toggleOn) R.string.common_on else R.string.common_off)

    AdVaultCard(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().background(AdVaultTheme.status.heroGradient).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusPill(stringResource(statusPillText(p.kind)), p.tone, Modifier.testTag("status_pill"))
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .size(168.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(ringColor.copy(alpha = 0.28f), ringColor.copy(alpha = 0.02f))))
                    .border(2.dp, ringColor.copy(alpha = 0.7f), CircleShape)
                    .toggleable(value = p.toggleOn, role = Role.Switch, onValueChange = onToggle)
                    .semantics {
                        contentDescription = toggleLabel
                        stateDescription = stateLabel
                    }
                    .testTag("shield_toggle"),
                contentAlignment = Alignment.Center,
            ) {
                if (p.kind == StatusKind.Starting) {
                    CircularProgressIndicator(Modifier.size(150.dp), color = ringColor, strokeWidth = 3.dp)
                }
                Icon(icon, null, tint = ringColor, modifier = Modifier.size(84.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.testTag("status_title"))
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("status_detail"),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .toggleable(value = p.toggleOn, role = Role.Switch, onValueChange = onToggle)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .testTag("protection_switch"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.VpnLock, null, tint = ringColor)
                Spacer(Modifier.width(12.dp))
                Text(toggleLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = p.toggleOn, onCheckedChange = null)
            }
        }
    }
}

private fun statusPillText(kind: StatusKind): Int = when (kind) {
    StatusKind.Stopped -> R.string.status_pill_stopped
    StatusKind.Starting -> R.string.status_pill_starting
    StatusKind.Running -> R.string.status_pill_running
    StatusKind.WaitingForNetwork -> R.string.status_pill_waiting
    StatusKind.Error -> R.string.status_pill_error
    StatusKind.Revoked -> R.string.status_pill_revoked
}

@Composable
private fun statusTexts(p: ProtectionUi): Pair<String, String> = when (p.kind) {
    StatusKind.Stopped -> stringResource(R.string.status_stopped_title) to stringResource(R.string.status_stopped_detail)
    StatusKind.Starting -> stringResource(R.string.status_starting_title) to stringResource(R.string.status_starting_detail)
    StatusKind.Running -> {
        val lines = buildList {
            add(stringResource(R.string.status_running_upstream, p.upstream ?: "—"))
            add(stringResource(R.string.status_running_apps, p.protectedApps ?: 0, p.excludedApps ?: 0))
            p.since?.let { add(stringResource(R.string.status_running_since, Format.duration(it))) }
            if (p.ipv6) add(stringResource(R.string.status_running_ipv6))
        }
        stringResource(R.string.status_running_title) to lines.joinToString("\n")
    }
    StatusKind.WaitingForNetwork -> stringResource(R.string.status_waiting_title) to stringResource(R.string.status_waiting_detail)
    StatusKind.Error -> stringResource(R.string.status_error_title) to (p.message ?: stringResource(R.string.status_error_unknown))
    StatusKind.Revoked -> stringResource(R.string.status_revoked_title) to stringResource(R.string.status_revoked_detail)
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WeekChartCard(points: List<DailyPoint>) {
    AdVaultCard(Modifier.fillMaxWidth()) {
        if (points.isEmpty() || points.all { it.total == 0L }) {
            Text(
                stringResource(R.string.dashboard_week_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AdVaultCard
        }
        val totalColor = MaterialTheme.colorScheme.surfaceContainerHighest
        val blockedColor = MaterialTheme.colorScheme.primary
        val max = points.maxOf { it.total }.coerceAtLeast(1)
        val description = stringResource(
            R.string.dashboard_week_cd,
            points.sumOf { it.blocked },
            points.sumOf { it.total },
        )
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
            val n = points.size
            val slot = size.width / n
            val barW = (slot * 0.55f).coerceAtMost(28.dp.toPx())
            val r = CornerRadius(4.dp.toPx())
            points.forEachIndexed { i, p ->
                val x = i * slot + (slot - barW) / 2
                val hTotal = size.height * (p.total.toFloat() / max)
                val hBlocked = size.height * (p.blocked.toFloat() / max)
                drawRoundRect(totalColor, Offset(x, size.height - hTotal), Size(barW, hTotal), r)
                drawRoundRect(blockedColor, Offset(x, size.height - hBlocked), Size(barW, hBlocked), r)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            points.forEach { p ->
                Text(
                    Format.weekday(p.day),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(blockedColor); Text(stringResource(R.string.dashboard_week_blocked), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(16.dp))
            LegendDot(totalColor); Text(stringResource(R.string.dashboard_week_total), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.padding(end = 6.dp).size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun OtherVpnDialog(alwaysOnOther: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit, onOpenVpnSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.VpnLock, null) },
        title = { Text(stringResource(R.string.dialog_other_vpn_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dialog_other_vpn_body))
                if (alwaysOnOther) {
                    Text(stringResource(R.string.dialog_other_vpn_always_on), color = AdVaultTheme.status.warning)
                    TextButton(onClick = onOpenVpnSettings) { Text(stringResource(R.string.action_open_vpn_settings)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.dialog_other_vpn_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_other_vpn_keep)) } },
    )
}

@Composable
private fun EstimateDialog(bytesPerRequest: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DataSaverOn, null) },
        title = { Text(stringResource(R.string.dialog_estimate_title)) },
        text = { Text(stringResource(R.string.dialog_estimate_body, Format.bytes(bytesPerRequest.toLong()))) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
