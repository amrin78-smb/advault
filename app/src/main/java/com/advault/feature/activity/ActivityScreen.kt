package com.advault.feature.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.CenteredLoading
import com.advault.feature.common.ConfirmDialog
import com.advault.feature.common.EmptyState
import com.advault.feature.common.Format
import com.advault.feature.common.InfoBanner
import com.advault.feature.common.MonoStyle
import com.advault.feature.common.StatusPill
import com.advault.feature.common.StatusTone
import com.advault.feature.common.Tag
import com.advault.feature.common.resolve

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityRoute(
    onOpenSettings: () -> Unit,
    onBack: (() -> Unit)?,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it.resolve(context)) } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            AdVaultTopBar(stringResource(R.string.activity_title), onBack = onBack, scrollBehavior = scroll, actions = {
                IconButton(onClick = { confirmClear = true }, enabled = state.rows.isNotEmpty() || state.filter.isActive) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.activity_clear))
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ActivityContent(
            state = state,
            onPackage = viewModel::setPackage,
            onProvider = viewModel::setProvider,
            onBlockedOnly = viewModel::setBlockedOnly,
            onClearFilters = viewModel::clearFilters,
            onOpenSettings = onOpenSettings,
            contentPadding = padding,
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.activity_clear_title),
            text = stringResource(R.string.activity_clear_body),
            confirmLabel = stringResource(R.string.action_clear),
            destructive = true,
            icon = Icons.Outlined.DeleteSweep,
            onConfirm = { confirmClear = false; viewModel.clearHistory() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
fun ActivityContent(
    state: ActivityUiState,
    onPackage: (String?) -> Unit,
    onProvider: (String?) -> Unit,
    onBlockedOnly: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OptionChip(
                    allLabel = stringResource(R.string.activity_filter_all_apps),
                    options = state.appOptions,
                    selectedId = state.filter.packageName,
                    onSelect = onPackage,
                )
                OptionChip(
                    allLabel = stringResource(R.string.activity_filter_all_providers),
                    options = state.providerOptions,
                    selectedId = state.filter.providerId,
                    onSelect = onProvider,
                )
                FilterChip(
                    selected = state.filter.blockedOnly,
                    onClick = { onBlockedOnly(!state.filter.blockedOnly) },
                    label = { Text(stringResource(R.string.activity_filter_blocked_only)) },
                )
            }
        }
        if (!state.loggingEnabled && state.rows.isNotEmpty()) {
            item {
                InfoBanner(
                    stringResource(R.string.activity_logging_off_banner),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    action = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) } },
                )
            }
        }
        when {
            state.loading -> item { CenteredLoading(Modifier.padding(48.dp)) }
            state.rows.isEmpty() && !state.loggingEnabled -> item {
                EmptyState(
                    icon = Icons.Outlined.HistoryToggleOff,
                    title = stringResource(R.string.activity_logging_off_title),
                    message = stringResource(R.string.activity_logging_off_body),
                    action = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) } },
                )
            }
            state.rows.isEmpty() && state.filter.isActive -> item {
                EmptyState(
                    icon = Icons.Outlined.FilterAltOff,
                    title = stringResource(R.string.activity_no_match_title),
                    message = stringResource(R.string.activity_no_match_body),
                    action = { TextButton(onClick = onClearFilters) { Text(stringResource(R.string.activity_clear_filters)) } },
                )
            }
            state.rows.isEmpty() -> item {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.ListAlt,
                    title = stringResource(R.string.activity_empty_title),
                    message = stringResource(R.string.activity_empty_body, state.retentionDays),
                )
            }
            else -> {
                var lastDay: String? = null
                state.rows.forEach { row ->
                    val day = Format.day(row.timestamp)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "h_$day") {
                            Text(
                                day,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                    }
                    item(key = row.id) {
                        ActivityEntryRow(row)
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item {
                    Text(
                        stringResource(R.string.activity_footer, ActivityViewModel.LIMIT, state.retentionDays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionChip(allLabel: String, options: List<FilterOption>, selectedId: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.id == selectedId }?.label ?: selectedId
    Box {
        FilterChip(
            selected = selectedId != null,
            onClick = { open = true },
            label = { Text(selectedLabel ?: allLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { onSelect(null); open = false })
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o.label) }, onClick = { onSelect(o.id); open = false })
            }
        }
    }
}


/** One activity log entry. Shared with the app detail screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityEntryRow(row: ActivityRowUi, showApp: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (row.blocked) Icons.Outlined.Block else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (row.blocked) com.advault.feature.common.AdVaultTheme.status.error else com.advault.feature.common.AdVaultTheme.status.active,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.domain, style = MonoStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                add(Format.time(row.timestamp))
                if (showApp) add(row.appLabel ?: row.packageName ?: stringResource(R.string.common_unknown_app))
                add(Format.queryType(row.queryType))
                if (row.cached) add(stringResource(R.string.activity_cached))
                else if (!row.blocked && row.latencyMs > 0) add("${row.latencyMs} ms")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusPill(
                    stringResource(if (row.blocked) R.string.activity_blocked else R.string.activity_allowed),
                    if (row.blocked) StatusTone.Error else StatusTone.Active,
                )
                row.providerName?.let { Tag(it, tone = StatusTone.Warning) }
                row.category?.let { Tag(it.displayName) }
            }
            Text(row.reason.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
