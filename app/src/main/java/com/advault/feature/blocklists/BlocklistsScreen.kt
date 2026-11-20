package com.advault.feature.blocklists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.core.common.FilterCategory
import com.advault.core.database.BlocklistEntity
import com.advault.data.models.BlocklistUpdateState
import com.advault.data.models.FilterEngineStatus
import com.advault.feature.common.AdVaultCard
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.AdVaultTheme
import com.advault.feature.common.CenteredLoading
import com.advault.feature.common.ConfirmDialog
import com.advault.feature.common.EmptyState
import com.advault.feature.common.Format
import com.advault.feature.common.KeyValueRow
import com.advault.feature.common.MonoStyle
import com.advault.feature.common.SectionHeader
import com.advault.feature.common.SettingsGroup
import com.advault.feature.common.StatusTone
import com.advault.feature.common.SwitchRow
import com.advault.feature.common.Tag
import com.advault.feature.common.resolve

/** Categories whose blocking may break app features; flagged in the UI. */
val FilterCategory.mayAffectFeatures: Boolean
    get() = this == FilterCategory.ANALYTICS || this == FilterCategory.TELEMETRY

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocklistsRoute(
    onBack: () -> Unit,
    onOpenCustomRules: () -> Unit,
    viewModel: BlocklistsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf<BlocklistEntity?>(null) }
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it.resolve(context)) } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val running = state.update as? BlocklistUpdateState.Running

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            AdVaultTopBar(stringResource(R.string.blocklists_title), onBack = onBack, scrollBehavior = scroll, actions = {
                IconButton(onClick = viewModel::openAddDialog) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.blocklists_add))
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    EngineStatusCard(state.engine)
                    Spacer(Modifier.height(12.dp))
                    if (running != null) {
                        AdVaultCard(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.blocklists_updating, running.currentListName, running.completed, running.total),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { if (running.total > 0) running.completed.toFloat() / running.total else 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = viewModel::updateAll, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.blocklists_update_now))
                            }
                            OutlinedButton(onClick = onOpenCustomRules, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.blocklists_custom_rules))
                            }
                        }
                    }
                    (state.update as? BlocklistUpdateState.Finished)?.let {
                        Text(
                            stringResource(R.string.blocklists_last_run, Format.relativeTime(it.report.finishedAt) ?: stringResource(R.string.common_never)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    SectionHeader(stringResource(R.string.blocklists_section_categories))
                    CategoryToggles(state.enabledCategories, viewModel::setCategoryEnabled)
                    SectionHeader(stringResource(R.string.blocklists_section_lists))
                }
            }
            when {
                state.loading -> item { CenteredLoading(Modifier.padding(32.dp)) }
                state.lists.isEmpty() -> item {
                    EmptyState(Icons.Outlined.Dns, stringResource(R.string.blocklists_empty_title), stringResource(R.string.blocklists_empty_body))
                }
                else -> items(state.lists, key = { it.id }) { list ->
                    BlocklistCard(
                        list = list,
                        updating = state.updatingListId == list.id || running?.currentListName == list.name,
                        onEnabled = { viewModel.setEnabled(list.id, it) },
                        onUpdate = { viewModel.updateList(list) },
                        onRemove = { confirmRemove = list },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    state.addDialog?.let { d ->
        AddListDialog(
            state = d,
            onChange = viewModel::editAddDialog,
            onSubmit = viewModel::submitAddDialog,
            onDismiss = viewModel::dismissAddDialog,
        )
    }
    confirmRemove?.let { list ->
        ConfirmDialog(
            title = stringResource(R.string.blocklists_remove_title),
            text = stringResource(R.string.blocklists_remove_body, list.name),
            confirmLabel = stringResource(R.string.action_remove),
            destructive = true,
            onConfirm = { viewModel.removeCustomList(list); confirmRemove = null },
            onDismiss = { confirmRemove = null },
        )
    }
}

@Composable
fun EngineStatusCard(engine: FilterEngineStatus) {
    AdVaultCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Format.count(engine.totalDomains), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.blocklists_engine_domains), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                engine.loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                engine.loaded -> Tag(stringResource(R.string.blocklists_engine_loaded), tone = StatusTone.Active)
                else -> Tag(stringResource(R.string.blocklists_engine_not_loaded), tone = StatusTone.Warning)
            }
        }
        Spacer(Modifier.height(8.dp))
        FilterCategory.entries.forEach { c ->
            val n = engine.domainsPerCategory[c] ?: 0
            KeyValueRow(c.displayName, Format.count(n))
        }
        KeyValueRow(stringResource(R.string.blocklists_engine_custom), stringResource(R.string.blocklists_engine_custom_value, engine.customBlockRules, engine.customAllowRules))
        if (engine.lastLoadedAt > 0) {
            KeyValueRow(
                stringResource(R.string.blocklists_engine_loaded_at),
                "${Format.relativeTime(engine.lastLoadedAt)} · ${engine.loadDurationMs} ms",
            )
        }
    }
}

@Composable
fun CategoryToggles(enabled: Set<FilterCategory>, onToggle: (FilterCategory, Boolean) -> Unit) {
    SettingsGroup {
        FilterCategory.entries.forEach { c ->
            SwitchRow(
                title = c.displayName,
                subtitle = c.description + if (c.mayAffectFeatures) "\n" + stringResource(R.string.blocklists_category_warning) else "",
                checked = c in enabled,
                onCheckedChange = { onToggle(c, it) },
            )
        }
    }
    Text(
        stringResource(R.string.blocklists_category_mode_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlocklistCard(
    list: BlocklistEntity,
    updating: Boolean,
    onEnabled: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdVaultCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(list.name, style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(list.category.displayName, tone = if (list.category.mayAffectFeatures) StatusTone.Warning else StatusTone.Info)
                    Tag(stringResource(if (list.builtIn) R.string.blocklists_builtin else R.string.blocklists_custom))
                    Tag(list.format.name.lowercase())
                }
            }
            Switch(checked = list.enabled, onCheckedChange = onEnabled)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.blocklists_domain_count, Format.count(list.ruleCount)) + " · " +
                stringResource(R.string.blocklists_updated, Format.relativeTime(list.lastUpdated) ?: stringResource(R.string.common_never)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (list.invalidRuleCount > 0 || list.duplicateCount > 0) {
            Text(
                stringResource(R.string.blocklists_skipped, list.invalidRuleCount, list.duplicateCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        list.url?.let { Text(it, style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        if (list.attribution.isNotBlank()) {
            Text(list.attribution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        list.lastError?.let {
            Text(stringResource(R.string.blocklists_last_error, it), style = MaterialTheme.typography.bodySmall, color = AdVaultTheme.status.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (!list.builtIn) {
                TextButton(onClick = onRemove) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_remove))
                }
            }
            if (list.url != null) {
                if (updating) CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
                else TextButton(onClick = onUpdate) { Text(stringResource(R.string.blocklists_update_one)) }
            }
        }
    }
}

@Composable
private fun AddListDialog(
    state: AddListDialogState,
    onChange: ((AddListDialogState) -> AddListDialogState) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        title = { Text(stringResource(R.string.blocklists_add_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { v -> onChange { it.copy(name = v) } },
                    label = { Text(stringResource(R.string.blocklists_add_name)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { v -> onChange { it.copy(url = v) } },
                    label = { Text(stringResource(R.string.blocklists_add_url)) },
                    supportingText = { Text(stringResource(R.string.blocklists_add_url_hint)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.blocklists_add_category), style = MaterialTheme.typography.labelLarge)
                FilterCategory.entries.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().selectable(c == state.category, role = Role.RadioButton, onClick = { onChange { it.copy(category = c) } }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = c == state.category, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(c.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !state.submitting) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
