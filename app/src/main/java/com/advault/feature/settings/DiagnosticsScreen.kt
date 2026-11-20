@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.advault.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.feature.blocklists.EngineStatusCard
import com.advault.feature.common.AdVaultCard
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.Format
import com.advault.feature.common.KeyValueRow
import com.advault.feature.common.SectionHeader
import com.advault.feature.common.SystemIntents
import com.advault.feature.common.WarningBanner
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsRoute(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val yes = stringResource(R.string.common_yes)
    val no = stringResource(R.string.common_no)
    fun b(v: Boolean) = if (v) yes else no
    val copiedMsg = stringResource(R.string.diagnostics_copied)

    Scaffold(
        topBar = {
            AdVaultTopBar(stringResource(R.string.diagnostics_title), onBack = onBack, actions = {
                IconButton(onClick = viewModel::refresh) { Icon(Icons.Outlined.Refresh, stringResource(R.string.diagnostics_refresh)) }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val d = ui.vpn
            if (d.privateDnsActive || d.anotherVpnActive || !d.upstreamHealthy) {
                WarningBanner(
                    buildList {
                        if (d.privateDnsActive) add(stringResource(R.string.diagnostics_warn_private_dns))
                        if (d.anotherVpnActive) add(stringResource(R.string.diagnostics_warn_other_vpn))
                        if (!d.upstreamHealthy) add(stringResource(R.string.diagnostics_warn_upstream))
                    }.joinToString("\n"),
                    title = stringResource(R.string.diagnostics_warn_title),
                )
            }
            SectionHeader(stringResource(R.string.diagnostics_section_vpn))
            AdVaultCard(Modifier.fillMaxWidth()) {
                KeyValueRow(stringResource(R.string.diagnostics_state), DiagnosticsReport.stateLabel(ui.state))
                KeyValueRow(stringResource(R.string.diagnostics_network), d.activeNetwork)
                KeyValueRow(stringResource(R.string.diagnostics_private_dns), b(d.privateDnsActive) + (d.privateDnsServerName?.let { " · $it" } ?: ""))
                KeyValueRow(stringResource(R.string.diagnostics_other_vpn), b(d.anotherVpnActive))
                KeyValueRow(stringResource(R.string.diagnostics_always_on_other), b(d.alwaysOnVpnOtherApp))
                KeyValueRow(stringResource(R.string.diagnostics_upstream_healthy), b(d.upstreamHealthy))
                KeyValueRow(stringResource(R.string.diagnostics_upstream_error), d.lastUpstreamError ?: stringResource(R.string.common_none))
                KeyValueRow(stringResource(R.string.diagnostics_latency), "${d.averageUpstreamLatencyMs} ms")
                KeyValueRow(stringResource(R.string.diagnostics_cache), "${Format.count(d.cacheEntries)} · ${Format.percent(d.cacheHitRate)}")
                KeyValueRow(stringResource(R.string.diagnostics_session), "${Format.count(d.queriesThisSession)} / ${Format.count(d.blockedThisSession)}")
                KeyValueRow(stringResource(R.string.diagnostics_restarts), d.restartCount.toString())
            }
            if (d.notes.isNotEmpty()) {
                SectionHeader(stringResource(R.string.diagnostics_section_notes))
                AdVaultCard(Modifier.fillMaxWidth()) {
                    d.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }
            SectionHeader(stringResource(R.string.diagnostics_section_engine))
            EngineStatusCard(ui.engine)
            SectionHeader(stringResource(R.string.diagnostics_section_providers))
            AdVaultCard(Modifier.fillMaxWidth()) {
                val p = ui.providerInfo
                if (p == null) Text(stringResource(R.string.rewards_db_not_loaded))
                else {
                    KeyValueRow(stringResource(R.string.rewards_db_version), "${p.dataVersion} (schema ${p.schemaVersion})")
                    KeyValueRow(stringResource(R.string.rewards_db_updated_at), p.updatedAt)
                    KeyValueRow(stringResource(R.string.rewards_db_source), p.source)
                    KeyValueRow(stringResource(R.string.rewards_db_counts), stringResource(R.string.rewards_db_counts_value, p.providerCount, p.domainCount))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    SystemIntents.copyToClipboard(context, "AdVault diagnostics", DiagnosticsReport.build(ui))
                    scope.launch { snackbar.showSnackbar(copiedMsg) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostics_copy))
            }
            Text(
                stringResource(R.string.diagnostics_copy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
            )
        }
    }
}
