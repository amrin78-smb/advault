package com.advault.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.advault.core.common.BlockResponse
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.core.common.UpdateFrequency
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Upstream resolver presets. Custom resolvers use [UpstreamProtocol] + address/host fields. */
enum class UpstreamPreset(
    val displayName: String,
    val protocol: UpstreamProtocol,
    /** IP addresses for plain DNS (used as bootstrap for DoT/DoH too). */
    val addresses: List<String>,
    /** DoH URL or DoT hostname. */
    val endpoint: String?,
) {
    SYSTEM("Network default (DHCP)", UpstreamProtocol.PLAIN, emptyList(), null),
    CLOUDFLARE("Cloudflare 1.1.1.1", UpstreamProtocol.PLAIN, listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"), null),
    CLOUDFLARE_DOH("Cloudflare (DNS-over-HTTPS)", UpstreamProtocol.DOH, listOf("1.1.1.1", "1.0.0.1"), "https://cloudflare-dns.com/dns-query"),
    GOOGLE("Google 8.8.8.8", UpstreamProtocol.PLAIN, listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888"), null),
    GOOGLE_DOH("Google (DNS-over-HTTPS)", UpstreamProtocol.DOH, listOf("8.8.8.8", "8.8.4.4"), "https://dns.google/dns-query"),
    QUAD9("Quad9 9.9.9.9", UpstreamProtocol.PLAIN, listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe"), null),
    QUAD9_DOT("Quad9 (DNS-over-TLS)", UpstreamProtocol.DOT, listOf("9.9.9.9", "149.112.112.112"), "dns.quad9.net"),
    CUSTOM("Custom", UpstreamProtocol.PLAIN, emptyList(), null),
}

enum class UpstreamProtocol { PLAIN, DOT, DOH }

data class AppSettings(
    /** The user's intent: protection should be running. Used for boot/auto-restart. */
    val protectionDesired: Boolean = false,
    val defaultMode: FilterMode = FilterMode.AGGRESSIVE,
    val enabledCategories: Set<FilterCategory> = FilterCategory.entries.toSet(),
    val upstreamPreset: UpstreamPreset = UpstreamPreset.CLOUDFLARE,
    val customUpstreamProtocol: UpstreamProtocol = UpstreamProtocol.PLAIN,
    /** Comma separated IPs for PLAIN, hostname for DOT, URL for DOH. */
    val customUpstream: String = "",
    val dnsTimeoutMs: Int = 4000,
    val blockResponse: BlockResponse = BlockResponse.NULL_IP,
    val dnsCacheEnabled: Boolean = true,
    val updateFrequency: UpdateFrequency = UpdateFrequency.DAILY,
    val updateOnWifiOnly: Boolean = false,
    val startOnBoot: Boolean = true,
    val showStatsInNotification: Boolean = true,
    val activityLoggingEnabled: Boolean = true,
    val logRetentionDays: Int = 7,
    val onboardingComplete: Boolean = false,
    /** Average bytes an ad request would have transferred; used only for the clearly-labelled estimate. */
    val estimatedBytesPerBlockedRequest: Int = 25_000,
    val diagnosticLogging: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val protectionDesired = booleanPreferencesKey("protection_desired")
        val defaultMode = stringPreferencesKey("default_mode")
        val categories = stringSetPreferencesKey("enabled_categories")
        val upstreamPreset = stringPreferencesKey("upstream_preset")
        val customProtocol = stringPreferencesKey("custom_upstream_protocol")
        val customUpstream = stringPreferencesKey("custom_upstream")
        val dnsTimeout = intPreferencesKey("dns_timeout_ms")
        val blockResponse = stringPreferencesKey("block_response")
        val dnsCache = booleanPreferencesKey("dns_cache")
        val updateFrequency = stringPreferencesKey("update_frequency")
        val wifiOnly = booleanPreferencesKey("update_wifi_only")
        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val notifStats = booleanPreferencesKey("notification_stats")
        val activityLogging = booleanPreferencesKey("activity_logging")
        val logRetention = intPreferencesKey("log_retention_days")
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val bytesPerBlocked = intPreferencesKey("bytes_per_blocked")
        val diagnostic = booleanPreferencesKey("diagnostic_logging")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            protectionDesired = p[K.protectionDesired] ?: d.protectionDesired,
            defaultMode = p.enum(K.defaultMode, d.defaultMode),
            enabledCategories = p[K.categories]
                ?.mapNotNull { n -> FilterCategory.entries.firstOrNull { it.name == n } }?.toSet()
                ?: d.enabledCategories,
            upstreamPreset = p.enum(K.upstreamPreset, d.upstreamPreset),
            customUpstreamProtocol = p.enum(K.customProtocol, d.customUpstreamProtocol),
            customUpstream = p[K.customUpstream] ?: d.customUpstream,
            dnsTimeoutMs = p[K.dnsTimeout] ?: d.dnsTimeoutMs,
            blockResponse = p.enum(K.blockResponse, d.blockResponse),
            dnsCacheEnabled = p[K.dnsCache] ?: d.dnsCacheEnabled,
            updateFrequency = p.enum(K.updateFrequency, d.updateFrequency),
            updateOnWifiOnly = p[K.wifiOnly] ?: d.updateOnWifiOnly,
            startOnBoot = p[K.startOnBoot] ?: d.startOnBoot,
            showStatsInNotification = p[K.notifStats] ?: d.showStatsInNotification,
            activityLoggingEnabled = p[K.activityLogging] ?: d.activityLoggingEnabled,
            logRetentionDays = p[K.logRetention] ?: d.logRetentionDays,
            onboardingComplete = p[K.onboarding] ?: d.onboardingComplete,
            estimatedBytesPerBlockedRequest = p[K.bytesPerBlocked] ?: d.estimatedBytesPerBlockedRequest,
            diagnosticLogging = p[K.diagnostic] ?: d.diagnosticLogging,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setProtectionDesired(v: Boolean) = edit { it[K.protectionDesired] = v }
    suspend fun setDefaultMode(v: FilterMode) = edit { it[K.defaultMode] = v.name }
    suspend fun setCategoryEnabled(c: FilterCategory, enabled: Boolean) {
        val cur = current().enabledCategories
        val next = if (enabled) cur + c else cur - c
        edit { it[K.categories] = next.map(FilterCategory::name).toSet() }
    }
    suspend fun setUpstreamPreset(v: UpstreamPreset) = edit { it[K.upstreamPreset] = v.name }
    suspend fun setCustomUpstream(protocol: UpstreamProtocol, value: String) = edit {
        it[K.customProtocol] = protocol.name; it[K.customUpstream] = value.trim()
    }
    suspend fun setDnsTimeoutMs(v: Int) = edit { it[K.dnsTimeout] = v.coerceIn(500, 15_000) }
    suspend fun setBlockResponse(v: BlockResponse) = edit { it[K.blockResponse] = v.name }
    suspend fun setDnsCacheEnabled(v: Boolean) = edit { it[K.dnsCache] = v }
    suspend fun setUpdateFrequency(v: UpdateFrequency) = edit { it[K.updateFrequency] = v.name }
    suspend fun setUpdateOnWifiOnly(v: Boolean) = edit { it[K.wifiOnly] = v }
    suspend fun setStartOnBoot(v: Boolean) = edit { it[K.startOnBoot] = v }
    suspend fun setShowStatsInNotification(v: Boolean) = edit { it[K.notifStats] = v }
    suspend fun setActivityLoggingEnabled(v: Boolean) = edit { it[K.activityLogging] = v }
    suspend fun setLogRetentionDays(v: Int) = edit { it[K.logRetention] = v.coerceIn(1, 90) }
    suspend fun setOnboardingComplete(v: Boolean) = edit { it[K.onboarding] = v }
    suspend fun setEstimatedBytesPerBlockedRequest(v: Int) = edit { it[K.bytesPerBlocked] = v.coerceIn(0, 10_000_000) }
    suspend fun setDiagnosticLogging(v: Boolean) = edit { it[K.diagnostic] = v }
}
