package com.advault.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.advault.BuildConfig
import com.advault.core.database.BlocklistEntity
import com.advault.core.preferences.AppSettings
import com.advault.data.models.FilterEngineStatus
import com.advault.data.models.ProtectionState
import com.advault.data.models.ProviderDatabaseInfo
import com.advault.data.models.VpnDiagnostics
import com.advault.data.repository.BlocklistRepository
import com.advault.data.repository.FilterEngine
import com.advault.data.repository.ProtectionController
import com.advault.data.repository.ProviderRepository
import com.advault.feature.common.Format
import com.advault.feature.common.SettingsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DiagnosticsUiState(
    val state: ProtectionState = ProtectionState.Stopped,
    val vpn: VpnDiagnostics = VpnDiagnostics(),
    val engine: FilterEngineStatus = FilterEngineStatus(),
    val providerInfo: ProviderDatabaseInfo? = null,
    val settings: AppSettings? = null,
)

object DiagnosticsReport {
    fun stateLabel(s: ProtectionState): String = when (s) {
        ProtectionState.Stopped -> "Stopped"
        ProtectionState.Starting -> "Starting"
        is ProtectionState.Running -> "Running (since ${Format.dateTime(s.since)}, upstream ${s.upstreamDescription}, " +
            "${s.protectedAppCount} protected / ${s.excludedAppCount} excluded, IPv6 ${if (s.ipv6) "on" else "off"})"
        is ProtectionState.WaitingForNetwork -> "Waiting for network (since ${Format.dateTime(s.since)})"
        is ProtectionState.Error -> "Error: ${s.message} (recoverable=${s.recoverable})"
        is ProtectionState.Revoked -> "Revoked: ${s.message}"
    }

    /** Plain-text report for the clipboard. Contains no browsing history or domain names. */
    fun build(ui: DiagnosticsUiState, now: Long = System.currentTimeMillis()): String = buildString {
        val d = ui.vpn
        appendLine("AdVault diagnostics — ${Format.dateTime(now)}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        appendLine("[Protection]")
        appendLine("State: ${stateLabel(ui.state)}")
        appendLine("Active network: ${d.activeNetwork}")
        appendLine("Private DNS active: ${d.privateDnsActive}${d.privateDnsServerName?.let { " ($it)" } ?: ""}")
        appendLine("Another VPN active: ${d.anotherVpnActive}")
        appendLine("Always-on VPN (other app): ${d.alwaysOnVpnOtherApp}")
        appendLine("Upstream healthy: ${d.upstreamHealthy}")
        appendLine("Last upstream error: ${d.lastUpstreamError ?: "none"}")
        appendLine("Average upstream latency: ${d.averageUpstreamLatencyMs} ms")
        appendLine("Cache entries: ${d.cacheEntries}, hit rate ${Format.percent(d.cacheHitRate)}")
        appendLine("Queries this session: ${d.queriesThisSession}, blocked ${d.blockedThisSession}")
        appendLine("Service restarts: ${d.restartCount}")
        d.notes.forEach { appendLine("Note: $it") }
        appendLine()
        appendLine("[Filter engine]")
        val e = ui.engine
        appendLine("Loaded: ${e.loaded}, loading: ${e.loading}")
        appendLine("Total domains: ${e.totalDomains}")
        e.domainsPerCategory.forEach { (c, n) -> appendLine("  ${c.name}: $n") }
        appendLine("Custom rules: ${e.customBlockRules} block / ${e.customAllowRules} allow")
        appendLine("Last loaded: ${if (e.lastLoadedAt > 0) Format.dateTime(e.lastLoadedAt) else "never"} (${e.loadDurationMs} ms)")
        appendLine()
        appendLine("[Provider database]")
        val p = ui.providerInfo
        if (p == null) appendLine("Not loaded")
        else appendLine("Schema ${p.schemaVersion}, data ${p.dataVersion}, updated ${p.updatedAt}, source ${p.source}, ${p.providerCount} providers / ${p.domainCount} domains")
        ui.settings?.let { s ->
            appendLine()
            appendLine("[Settings]")
            appendLine("Mode: ${s.defaultMode}, categories: ${s.enabledCategories.joinToString { it.name }}")
            appendLine("Upstream: ${s.upstreamPreset}${if (s.upstreamPreset.name == "CUSTOM") " ${s.customUpstreamProtocol} ${s.customUpstream}" else ""}")
            appendLine("Timeout: ${s.dnsTimeoutMs} ms, block response: ${s.blockResponse}, cache: ${s.dnsCacheEnabled}")
            appendLine("Updates: ${s.updateFrequency}, Wi-Fi only: ${s.updateOnWifiOnly}, start on boot: ${s.startOnBoot}")
            appendLine("Activity logging: ${s.activityLoggingEnabled} (${s.logRetentionDays} d), diagnostic logging: ${s.diagnosticLogging}")
        }
    }
}

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val controller: ProtectionController,
    filterEngine: FilterEngine,
    providerRepository: ProviderRepository,
    settingsSource: SettingsSource,
) : ViewModel() {
    val state: StateFlow<DiagnosticsUiState> = combine(
        controller.state,
        controller.diagnostics,
        filterEngine.status,
        providerRepository.info,
        settingsSource.settings().map<AppSettings, AppSettings?> { it }.onStart { emit(null) },
    ) { s, d, e, p, settings -> DiagnosticsUiState(s, d, e, p, settings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun refresh() = controller.refreshDiagnostics()
}

@HiltViewModel
class AboutViewModel @Inject constructor(blocklists: BlocklistRepository) : ViewModel() {
    val lists: StateFlow<List<BlocklistEntity>> = blocklists.observeBlocklists()
        .map { l -> l.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
