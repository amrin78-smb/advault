package com.advault.feature.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.core.common.AppProtection
import com.advault.core.common.FilterMode
import com.advault.data.models.InstalledApp
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.AppIcon
import com.advault.feature.common.CenteredLoading
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
fun AppsRoute(onOpenApp: (String) -> Unit, viewModel: AppsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it.resolve(context)) } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var sortMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            AdVaultTopBar(stringResource(R.string.apps_title), scrollBehavior = scroll, actions = {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.apps_sort))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    AppSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(stringResource(sortLabel(sort))) },
                            leadingIcon = { RadioButton(selected = state.query.sort == sort, onClick = null) },
                            onClick = { viewModel.setSort(sort); sortMenu = false },
                        )
                    }
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AppsContent(
            state = state,
            onSearch = viewModel::setSearch,
            onFilter = viewModel::setFilter,
            onShowSystem = viewModel::setShowSystem,
            onQuickToggle = viewModel::quickToggle,
            onOpenApp = onOpenApp,
            contentPadding = padding,
        )
    }
}

private fun sortLabel(sort: AppSort) = when (sort) {
    AppSort.NAME -> R.string.apps_sort_name
    AppSort.BLOCKED -> R.string.apps_sort_blocked
    AppSort.QUERIES -> R.string.apps_sort_queries
}

private fun filterLabel(filter: AppFilter) = when (filter) {
    AppFilter.ALL -> R.string.apps_filter_all
    AppFilter.USER -> R.string.apps_filter_user
    AppFilter.GAMES -> R.string.apps_filter_games
    AppFilter.EXCLUDED -> R.string.apps_filter_excluded
}

@Composable
fun AppsContent(
    state: AppsUiState,
    onSearch: (String) -> Unit,
    onFilter: (AppFilter) -> Unit,
    onShowSystem: (Boolean) -> Unit,
    onQuickToggle: (InstalledApp) -> Unit,
    onOpenApp: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    var showExplainer by rememberSaveable { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = state.query.search,
                    onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.apps_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (state.query.search.isNotEmpty()) {
                            IconButton(onClick = { onSearch("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppFilter.entries.forEach { f ->
                        FilterChip(
                            selected = state.query.filter == f,
                            onClick = { onFilter(f) },
                            label = {
                                Text(
                                    if (f == AppFilter.EXCLUDED) stringResource(R.string.apps_filter_excluded_count, state.excludedCount)
                                    else stringResource(filterLabel(f)),
                                )
                            },
                        )
                    }
                    FilterChip(
                        selected = state.query.showSystem,
                        onClick = { onShowSystem(!state.query.showSystem) },
                        label = { Text(stringResource(R.string.apps_filter_system)) },
                    )
                }
                if (showExplainer) {
                    InfoBanner(
                        text = stringResource(R.string.apps_explainer),
                        action = {
                            androidx.compose.material3.TextButton(onClick = { showExplainer = false }) {
                                Text(stringResource(R.string.apps_explainer_dismiss))
                            }
                        },
                    )
                    Spacer(Modifier.padding(4.dp))
                }
            }
        }
        when {
            state.loading -> item { CenteredLoading(Modifier.padding(48.dp)) }
            state.rows.isEmpty() -> item {
                EmptyState(
                    icon = Icons.Outlined.Apps,
                    title = stringResource(if (state.totalApps == 0) R.string.apps_empty_title else R.string.apps_no_match_title),
                    message = stringResource(if (state.totalApps == 0) R.string.apps_empty_body else R.string.apps_no_match_body),
                )
            }
            else -> {
                item {
                    Text(
                        stringResource(R.string.apps_count, state.rows.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(state.rows, key = { it.app.packageName }) { row ->
                    AppRow(row, state.defaultMode, onToggle = { onQuickToggle(row.app) }, onClick = { onOpenApp(row.app.packageName) })
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
fun protectionLabel(protection: AppProtection, defaultMode: FilterMode): String = when (protection) {
    AppProtection.DEFAULT -> stringResource(R.string.apps_protection_default, defaultMode.displayName)
    AppProtection.AGGRESSIVE -> stringResource(R.string.apps_protection_aggressive)
    AppProtection.BALANCED -> stringResource(R.string.apps_protection_balanced)
    AppProtection.EXCLUDED -> stringResource(R.string.apps_protection_excluded)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRow(row: AppRowUi, defaultMode: FilterMode, onToggle: () -> Unit, onClick: () -> Unit) {
    val app = row.app
    val excluded = app.protection == AppProtection.EXCLUDED
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, app.icon, app.label)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusPill(protectionLabel(app.protection, defaultMode), if (excluded) StatusTone.Inactive else StatusTone.Active)
                if (app.isGame) Tag(stringResource(R.string.apps_badge_game), tone = StatusTone.Info)
                if (app.isSystem) Tag(stringResource(R.string.apps_badge_system))
                row.sdkNames.take(3).forEach { Tag(it, tone = StatusTone.Warning) }
                if (row.sdkNames.size > 3) Tag("+${row.sdkNames.size - 3}")
            }
            Text(
                if (app.totalQueries == 0L) stringResource(R.string.apps_no_queries)
                else stringResource(R.string.apps_query_counts, Format.compactCount(app.totalQueries), Format.compactCount(app.blockedQueries)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        val cd = stringResource(if (excluded) R.string.apps_toggle_protect_cd else R.string.apps_toggle_exclude_cd, app.label)
        Switch(
            checked = !excluded,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics { contentDescription = cd },
        )
    }
}
