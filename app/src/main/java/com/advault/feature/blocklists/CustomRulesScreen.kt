@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.advault.feature.blocklists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.core.common.RuleType
import com.advault.feature.common.AdVaultTheme
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.CenteredLoading
import com.advault.feature.common.EmptyState
import com.advault.feature.common.Format
import com.advault.feature.common.MonoStyle
import com.advault.feature.common.resolve

@Composable
fun CustomRulesRoute(onBack: () -> Unit, viewModel: CustomRulesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it.resolve(context)) } }

    Scaffold(
        topBar = { AdVaultTopBar(stringResource(R.string.rules_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                Tab(
                    selected = state.tab == RuleType.BLOCK,
                    onClick = { viewModel.setTab(RuleType.BLOCK) },
                    text = { Text(stringResource(R.string.rules_tab_block, state.blockCount)) },
                )
                Tab(
                    selected = state.tab == RuleType.ALLOW,
                    onClick = { viewModel.setTab(RuleType.ALLOW) },
                    text = { Text(stringResource(R.string.rules_tab_allow, state.allowCount)) },
                )
            }
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(if (state.tab == RuleType.BLOCK) R.string.rules_block_desc else R.string.rules_allow_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = viewModel::setInput,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.rules_input_label)) },
                        placeholder = { Text("ads.example.com") },
                        isError = state.error != null,
                        supportingText = { Text(state.error?.resolve() ?: stringResource(R.string.rules_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.add() }),
                        enabled = !state.submitting,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = viewModel::add, enabled = !state.submitting, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.action_add))
                    }
                }
            }
            when {
                state.loading -> CenteredLoading()
                state.rules.isEmpty() -> EmptyState(
                    Icons.Outlined.EditNote,
                    stringResource(R.string.rules_empty_title),
                    stringResource(if (state.tab == RuleType.BLOCK) R.string.rules_empty_block else R.string.rules_empty_allow),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rules, key = { it.id }) { rule ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (rule.type == RuleType.BLOCK) Icons.Outlined.Block else Icons.Outlined.CheckCircle,
                                null,
                                tint = if (rule.type == RuleType.BLOCK) AdVaultTheme.status.error else AdVaultTheme.status.active,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rule.domain, style = MonoStyle)
                                Text(
                                    stringResource(R.string.rules_added_at, Format.dateTime(rule.createdAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.remove(rule) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.rules_delete_cd, rule.domain))
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
