package com.advault.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.advault.R
import com.advault.core.common.BlockResponse
import com.advault.core.common.DomainUtils
import com.advault.core.common.FilterMode
import com.advault.core.common.UpdateFrequency
import com.advault.core.preferences.AppSettings
import com.advault.core.preferences.SettingsRepository
import com.advault.core.preferences.UpstreamPreset
import com.advault.core.preferences.UpstreamProtocol
import com.advault.data.repository.BlocklistRepository
import com.advault.data.repository.ProtectionController
import com.advault.data.repository.StatsRepository
import com.advault.feature.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Validation for a custom upstream resolver. Returns a string resource id describing the problem, or null if valid. */
object UpstreamValidation {
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")

    fun isIpv4(s: String) = IPV4.matches(s)

    fun isIpv6(s: String): Boolean {
        if (!s.contains(':') || !IPV6_CHARS.matches(s)) return false
        val doubleColons = Regex("::").findAll(s).count()
        if (doubleColons > 1 || s.contains(":::")) return false
        val groups = s.split(':').filter { it.isNotEmpty() }
        val last = groups.lastOrNull()
        val embeddedV4 = last != null && last.contains('.')
        if (embeddedV4 && !isIpv4(last!!)) return false
        val hexGroups = if (embeddedV4) groups.dropLast(1) else groups
        if (hexGroups.any { it.length > 4 || it.contains('.') }) return false
        val count = hexGroups.size + if (embeddedV4) 2 else 0
        return if (doubleColons == 1) count < 8 else count == 8
    }

    fun validate(protocol: UpstreamProtocol, raw: String): Int? {
        val value = raw.trim()
        if (value.isEmpty()) return R.string.dns_custom_error_empty
        return when (protocol) {
            UpstreamProtocol.PLAIN -> {
                val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty() || parts.any { !isIpv4(it) && !isIpv6(it) }) R.string.dns_custom_error_ip else null
            }
            UpstreamProtocol.DOT -> if (DomainUtils.isValidDomain(value.lowercase())) null else R.string.dns_custom_error_host
            UpstreamProtocol.DOH -> {
                if (!value.startsWith("https://", ignoreCase = true)) return R.string.dns_custom_error_https
                val host = value.substring(8).substringBefore('/').substringBefore(':')
                if (host.isEmpty() || (!DomainUtils.isValidDomain(host.lowercase()) && !isIpv4(host))) R.string.dns_custom_error_url else null
            }
        }
    }

    fun hint(protocol: UpstreamProtocol): Int = when (protocol) {
        UpstreamProtocol.PLAIN -> R.string.dns_custom_hint_plain
        UpstreamProtocol.DOT -> R.string.dns_custom_hint_dot
        UpstreamProtocol.DOH -> R.string.dns_custom_hint_doh
    }
}

data class SettingsUiState(val settings: AppSettings? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val controller: ProtectionController,
    private val blocklists: BlocklistRepository,
    private val stats: StatsRepository,
) : ViewModel() {

    private val messages = Channel<UiText>(Channel.BUFFERED)
    val messageFlow: Flow<UiText> = messages.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = repo.settings.map { SettingsUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun write(reloadVpn: Boolean = false, rescheduleUpdates: Boolean = false, block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch {
            repo.block()
            if (reloadVpn) controller.reload()
            if (rescheduleUpdates) blocklists.applyUpdateSchedule()
        }
    }

    fun setDefaultMode(v: FilterMode) = write { setDefaultMode(v) }
    fun setUpdateFrequency(v: UpdateFrequency) = write(rescheduleUpdates = true) { setUpdateFrequency(v) }
    fun setWifiOnly(v: Boolean) = write(rescheduleUpdates = true) { setUpdateOnWifiOnly(v) }
    fun setUpstreamPreset(v: UpstreamPreset) {
        // Switching to CUSTOM without a valid stored value would leave the VPN without a resolver; wait for Save.
        val s = state.value.settings
        val customReady = s != null && UpstreamValidation.validate(s.customUpstreamProtocol, s.customUpstream) == null
        write(reloadVpn = v != UpstreamPreset.CUSTOM || customReady) { setUpstreamPreset(v) }
    }

    /** Returns an error resource id, or null when saved. */
    fun saveCustomUpstream(protocol: UpstreamProtocol, value: String): Int? {
        UpstreamValidation.validate(protocol, value)?.let { return it }
        write(reloadVpn = true) {
            setCustomUpstream(protocol, value)
            setUpstreamPreset(UpstreamPreset.CUSTOM)
        }
        viewModelScope.launch { messages.send(UiText.Res(R.string.dns_custom_saved)) }
        return null
    }

    fun setDnsTimeout(ms: Int) = write { setDnsTimeoutMs(ms) }
    fun setBlockResponse(v: BlockResponse) = write { setBlockResponse(v) }
    fun setDnsCache(v: Boolean) = write { setDnsCacheEnabled(v) }
    fun setStartOnBoot(v: Boolean) = write { setStartOnBoot(v) }
    fun setStatsInNotification(v: Boolean) = write { setShowStatsInNotification(v) }
    fun setActivityLogging(v: Boolean) = write { setActivityLoggingEnabled(v) }
    fun setRetentionDays(v: Int) = write { setLogRetentionDays(v) }
    fun setBytesPerBlocked(v: Int) = write { setEstimatedBytesPerBlockedRequest(v) }
    fun setDiagnosticLogging(v: Boolean) = write { setDiagnosticLogging(v) }

    fun clearActivity() {
        viewModelScope.launch {
            stats.clearActivity()
            messages.send(UiText.Res(R.string.activity_cleared))
        }
    }

    fun clearStatistics() {
        viewModelScope.launch {
            stats.clearStatistics()
            messages.send(UiText.Res(R.string.settings_stats_cleared))
        }
    }

    companion object {
        val TIMEOUT_OPTIONS = listOf(1000, 2000, 3000, 4000, 6000, 8000, 10_000)
        val RETENTION_OPTIONS = listOf(1, 3, 7, 14, 30, 90)
        val BYTES_OPTIONS = listOf(0, 10_000, 25_000, 50_000, 100_000, 250_000)
    }
}
