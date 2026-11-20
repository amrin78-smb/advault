package com.advault.feature.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.core.common.AppProtection
import com.advault.core.common.FilterMode
import com.advault.feature.activity.ActivityEntryRow
import com.advault.feature.activity.ActivityMapper
import com.advault.feature.common.AdVaultCard
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.AppIcon
import com.advault.feature.common.CenteredLoading
import com.advault.feature.common.EmptyState
import com.advault.feature.common.Format
import com.advault.feature.common.InfoBanner
import com.advault.feature.common.KeyValueRow
import com.advault.feature.common.MonoStyle
import com.advault.feature.common.SectionHeader
import com.advault.feature.common.StatusTone
import com.advault.feature.common.Tag
import com.advault.feature.common.resolve

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDetailRoute(
    onBack: () -> Unit,
    onOpenFullActivity: (String) -> Unit,
    onOpenProvider: (String) -> Unit,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it.resolve(context)) } }
    val app = state.app

    Scaffold(
        topBar = { AdVaultTopBar(app?.label ?: stringResource(R.string.app_detail_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> CenteredLoading(Modifier.padding(padding))
            app == null -> EmptyState(
                Icons.Outlined.Apps,
                stringResource(R.string.app_detail_missing_title),
                stringResource(R.string.app_detail_missing_body, viewModel.packageName),
                Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.packageName, app.icon, app.label, size = 56.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleLarge)
                                Text(app.packageName, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    if (app.isGame) Tag(stringResource(R.string.apps_badge_game), tone = StatusTone.Info)
                                    if (app.isSystem) Tag(stringResource(R.string.apps_badge_system))
                                    Tag("UID ${app.uid}")
                                }
                            }
                        }
                        SectionHeader(stringResource(R.string.app_detail_section_stats))
                        AdVaultCard(Modifier.fillMaxWidth()) {
                            KeyValueRow(stringResource(R.string.stat_total_queries), Format.count(app.totalQueries))
                            KeyValueRow(stringResource(R.string.stat_blocked_queries), Format.count(app.blockedQueries))
                            KeyValueRow(stringResource(R.string.app_detail_dns_bytes), Format.bytes(app.dnsBytes))
                        }

                        SectionHeader(stringResource(R.string.app_detail_section_protection))
                        ProtectionSelector(app.protection, state.defaultMode, viewModel::setProtection)
                        Spacer(Modifier.padding(4.dp))
                        InfoBanner(stringResource(R.string.app_detail_exclusion_note))

                        SectionHeader(stringResource(R.string.app_detail_section_sdks))
                        if (state.sdks.isEmpty() && state.unknownSdkIds.isEmpty()) {
                            Text(
                                stringResource(R.string.app_detail_no_sdks),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            AdVaultCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                                state.sdks.forEach { sdk ->
                                    com.advault.feature.common.SettingsRow(
                                        title = sdk.name,
                                        subtitle = sdk.description,
                                        onClick = { onOpenProvider(sdk.id) },
                                    )
                                }
                                state.unknownSdkIds.forEach { id -> com.advault.feature.common.SettingsRow(title = id) }
                            }
                            Text(
                                stringResource(R.string.app_detail_sdk_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }

                        SectionHeader(stringResource(R.string.app_detail_section_activity), action = {
                            if (state.activity.isNotEmpty()) {
                                androidx.compose.material3.TextButton(onClick = { onOpenFullActivity(app.packageName) }) {
                                    Text(stringResource(R.string.app_detail_open_activity))
                                }
                            }
                        })
                    }
                }
                if (state.activity.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Outlined.History,
                            stringResource(R.string.app_detail_no_activity_title),
                            stringResource(R.string.app_detail_no_activity_body),
                        )
                    }
                } else {
                    val rows = ActivityMapper.rows(state.activity.take(50), emptyMap(), state.providerNames)
                    items(rows, key = { it.id }) { row ->
                        ActivityEntryRow(row, showApp = false)
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item {
                        OutlinedButton(
                            onClick = { onOpenFullActivity(app.packageName) },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text(stringResource(R.string.app_detail_open_activity)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectionSelector(current: AppProtection, defaultMode: FilterMode, onSelect: (AppProtection) -> Unit) {
    AdVaultCard(Modifier.fillMaxWidth().selectableGroup(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
        AppProtection.entries.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == current, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == current, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(protectionLabel(option, defaultMode), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(
                            when (option) {
                                AppProtection.DEFAULT -> R.string.protection_desc_default
                                AppProtection.AGGRESSIVE -> R.string.protection_desc_aggressive
                                AppProtection.BALANCED -> R.string.protection_desc_balanced
                                AppProtection.EXCLUDED -> R.string.protection_desc_excluded
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
