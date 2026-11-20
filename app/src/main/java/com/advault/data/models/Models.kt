package com.advault.data.models

import android.graphics.drawable.Drawable
import com.advault.core.common.AdFormat
import com.advault.core.common.AppProtection
import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory

// ---------------------------------------------------------------- Protection / VPN

sealed interface ProtectionState {
    data object Stopped : ProtectionState
    data object Starting : ProtectionState
    data class Running(
        val since: Long,
        val upstreamDescription: String,
        val protectedAppCount: Int,
        val excludedAppCount: Int,
        val ipv6: Boolean,
    ) : ProtectionState
    /** Network is unavailable (e.g. airplane mode); service stays alive and resumes automatically. */
    data class WaitingForNetwork(val since: Long) : ProtectionState
    data class Error(val message: String, val recoverable: Boolean) : ProtectionState
    /** Another app holds the VPN, or the user revoked our VPN permission. */
    data class Revoked(val message: String) : ProtectionState
}

data class VpnDiagnostics(
    val activeNetwork: String = "Unknown",
    val privateDnsActive: Boolean = false,
    val privateDnsServerName: String? = null,
    val anotherVpnActive: Boolean = false,
    val alwaysOnVpnOtherApp: Boolean = false,
    val upstreamHealthy: Boolean = true,
    val lastUpstreamError: String? = null,
    val averageUpstreamLatencyMs: Int = 0,
    val cacheEntries: Int = 0,
    val cacheHitRate: Double = 0.0,
    val queriesThisSession: Long = 0,
    val blockedThisSession: Long = 0,
    val restartCount: Int = 0,
    val notes: List<String> = emptyList(),
)

// ---------------------------------------------------------------- Filtering

data class FilterVerdict(
    val blocked: Boolean,
    val reason: DecisionReason,
    val category: FilterCategory? = null,
    /** The rule or domain suffix that matched, if any. */
    val matchedRule: String? = null,
    val listId: String? = null,
)

data class FilterEngineStatus(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val totalDomains: Int = 0,
    val domainsPerCategory: Map<FilterCategory, Int> = emptyMap(),
    val customBlockRules: Int = 0,
    val customAllowRules: Int = 0,
    val lastLoadedAt: Long = 0,
    val loadDurationMs: Long = 0,
)

sealed interface BlocklistUpdateState {
    data object Idle : BlocklistUpdateState
    data class Running(val currentListName: String, val completed: Int, val total: Int) : BlocklistUpdateState
    data class Finished(val report: BlocklistUpdateReport) : BlocklistUpdateState
}

data class BlocklistUpdateReport(
    val finishedAt: Long,
    val updated: List<String>,
    val unchanged: List<String>,
    val failed: Map<String, String>,
)

// ---------------------------------------------------------------- Applications

data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val isGame: Boolean,
    val icon: Drawable?,
    val protection: AppProtection,
    /** Advertising SDKs detected from the app's declared components/metadata. */
    val detectedSdkProviderIds: List<String>,
    val totalQueries: Long,
    val blockedQueries: Long,
    val dnsBytes: Long,
)

// ---------------------------------------------------------------- Statistics

/** All values are measured from real filtered traffic except [estimatedBytesSaved]. */
data class DashboardStats(
    val totalQueries: Long = 0,
    val blockedQueries: Long = 0,
    val adsBlocked: Long = 0,
    val trackingBlocked: Long = 0,
    val measuredDnsBytes: Long = 0,
    /** ESTIMATE: blocked ad/tracking requests x configured average payload. Must be labelled as an estimate in UI. */
    val estimatedBytesSaved: Long = 0,
    val protectedApps: Int = 0,
    val lastBlocklistUpdate: Long = 0,
)

data class DailyPoint(val day: Long, val total: Long, val blocked: Long)

// ---------------------------------------------------------------- Provider intelligence

data class AdProvider(
    val id: String,
    val name: String,
    val description: String,
    val lastUpdated: String,
    val domains: List<ProviderDomain>,
    /** Class / package prefixes that identify the SDK inside an installed APK (e.g. "com.unity3d.ads"). */
    val sdkSignatures: List<String>,
    /** Whether the provider is known to verify rewarded completion server-side (SSV). */
    val supportsServerSideVerification: Boolean,
    /** Manifest <meta-data> keys (exact or boundary-delimited prefix) that identify the SDK. */
    val sdkMetaDataKeys: List<String> = emptyList(),
)

data class ProviderDomain(
    /** Domain or "*.suffix" pattern. */
    val pattern: String,
    val category: FilterCategory,
    val formats: List<AdFormat>,
    val note: String? = null,
    /**
     * False for shared infrastructure that also serves non-advertising functionality (e.g. Facebook
     * Login on graph.facebook.com). Such domains are used ONLY for classification and must never be
     * emitted as filter rules. Use AdProvider.blockableDomains() (intelligence.provider) for rules.
     */
    val blockable: Boolean = true,
)

data class ProviderDatabaseInfo(
    val schemaVersion: Int,
    val dataVersion: String,
    val updatedAt: String,
    val source: String, // "bundled" or URL
    val providerCount: Int,
    val domainCount: Int,
)

/** Match of a domain to a provider. */
data class ProviderMatch(val provider: AdProvider, val domain: ProviderDomain)

// ---------------------------------------------------------------- Reward Assistant

enum class RewardSupport(val displayName: String) {
    /** No ad SDK detected and no ad traffic observed. */
    NO_ADS_DETECTED("No advertising detected"),
    /** Ad SDK/traffic present; rewarded format not identified. */
    ADS_NO_REWARDED_EVIDENCE("Advertising detected, rewarded ads not identified"),
    /** Provider known to serve rewarded formats. The reward itself is controlled by the game. */
    REWARDED_POSSIBLE("Rewarded ads possible"),
    /** Known from compatibility data that rewards require a verified ad completion. */
    REQUIRES_VERIFIED_COMPLETION("Rewards require a verified ad view"),
    /** Compatibility data lists legitimate ad-free alternatives. */
    ALTERNATIVES_AVAILABLE("Ad-free alternatives available"),
}

/** What AdVault should do for this game's advertising. */
enum class GameAdBehaviour(val displayName: String, val description: String) {
    BLOCK_ALL("Block all ads", "Block every identified ad domain. Rewarded ads will fail to load, so rewards that require them will be unavailable."),
    ALLOW_REWARDED("Allow rewarded ads", "Excludes this game from filtering so you can choose to watch rewarded ads. Interstitial and banner ads will also show."),
    DEFAULT("Follow app protection", "Use this app's normal protection setting."),
}

data class ObservedProviderActivity(
    val providerId: String,
    val providerName: String,
    val hits: Long,
    val blocked: Long,
    val lastSeen: Long,
    /** Formats associated with the observed domains. Classification, not proof an ad was shown. */
    val associatedFormats: Set<AdFormat>,
)

data class GameCompatibility(
    val packageName: String,
    val title: String,
    val notes: String,
    val rewardRequiresVerifiedAd: Boolean?,
    val alternatives: List<String>,
    val knownIssuesWhenBlocking: List<String>,
    val lastVerified: String?,
)

data class GameRewardProfile(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val detectedSdks: List<AdProvider>,
    val observed: List<ObservedProviderActivity>,
    val compatibility: GameCompatibility?,
    val support: RewardSupport,
    /** Plain-language honest explanation shown to the user. */
    val explanation: String,
    val behaviour: GameAdBehaviour,
)
