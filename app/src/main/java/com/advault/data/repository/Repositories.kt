package com.advault.data.repository

import android.content.Intent
import com.advault.core.common.AppProtection
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.core.common.RuleType
import com.advault.core.database.ActivityLogEntity
import com.advault.core.database.BlocklistEntity
import com.advault.core.database.CustomRuleEntity
import com.advault.data.models.AdProvider
import com.advault.data.models.BlocklistUpdateReport
import com.advault.data.models.BlocklistUpdateState
import com.advault.data.models.DailyPoint
import com.advault.data.models.DashboardStats
import com.advault.data.models.FilterEngineStatus
import com.advault.data.models.FilterVerdict
import com.advault.data.models.GameAdBehaviour
import com.advault.data.models.GameRewardProfile
import com.advault.data.models.InstalledApp
import com.advault.data.models.ProtectionState
import com.advault.data.models.ProviderDatabaseInfo
import com.advault.data.models.ProviderMatch
import com.advault.data.models.VpnDiagnostics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Owned by vpn/. Starts/stops the local VPN and reports state. */
interface ProtectionController {
    val state: StateFlow<ProtectionState>
    val diagnostics: StateFlow<VpnDiagnostics>
    /** Returns the system consent intent if VPN permission is still needed, else null. */
    fun prepareIntent(): Intent?
    /** Starts protection. Caller must have obtained consent (prepareIntent() == null). */
    fun start()
    fun stop()
    /** Re-establishes the TUN interface so new app routing / settings take effect. No-op if stopped. */
    fun reload()
    /** Re-queries system network/VPN/private DNS info into [diagnostics]. */
    fun refreshDiagnostics()
}

/** Owned by filtering/. Hot-path domain decisions. Must be thread-safe and non-blocking. */
interface FilterEngine {
    val status: StateFlow<FilterEngineStatus>
    /** [domain] must already be normalised (lowercase, no trailing dot). */
    fun evaluate(domain: String, mode: FilterMode): FilterVerdict
    /** Rebuilds the in-memory matcher from enabled lists, custom rules and category settings. */
    suspend fun reload()
}

/** Owned by filtering/. */
interface BlocklistRepository {
    fun observeBlocklists(): Flow<List<BlocklistEntity>>
    val updateState: StateFlow<BlocklistUpdateState>
    suspend fun setEnabled(id: String, enabled: Boolean)
    /** Adds a user-defined HTTPS list and downloads it. */
    suspend fun addCustomList(name: String, url: String, category: FilterCategory): Result<BlocklistEntity>
    suspend fun removeCustomList(id: String)
    suspend fun updateAll(force: Boolean = false): BlocklistUpdateReport
    suspend fun updateList(id: String): Result<Int>
    fun observeCustomRules(): Flow<List<CustomRuleEntity>>
    /** Validates and adds a rule. Accepts "example.com" and "*.example.com". */
    suspend fun addCustomRule(input: String, type: RuleType): Result<CustomRuleEntity>
    suspend fun removeCustomRule(id: Long)
    /** Schedules/cancels periodic WorkManager updates according to settings. */
    suspend fun applyUpdateSchedule()
    /** Ensures bundled lists are installed on first run. */
    suspend fun ensureInitialized()
}

/** Owned by intelligence/. Installed applications plus per-app policy. */
interface AppRepository {
    /** Launchable + system apps with policy and stats. Icons loaded lazily is acceptable. */
    fun observeApps(): Flow<List<InstalledApp>>
    suspend fun setProtection(packageName: String, protection: AppProtection)
    /** Packages to pass to VpnService.Builder.addDisallowedApplication. Always includes our own package. */
    suspend fun excludedPackages(): Set<String>
    /** Effective filter mode for a uid; null means not filtered (excluded). Must be fast (cached). */
    fun modeForUid(uid: Int): FilterMode?
    /** Package name for a uid, cached. */
    fun packageForUid(uid: Int): String?
    suspend fun refresh()
}

/** Owned by intelligence/. Versioned provider database (bundled asset + downloadable updates). */
interface ProviderRepository {
    val providers: StateFlow<List<AdProvider>>
    val info: StateFlow<ProviderDatabaseInfo?>
    /** Fast, thread-safe classification of a normalised domain. */
    fun match(domain: String): ProviderMatch?
    suspend fun load()
    /** Downloads and validates a newer provider database over HTTPS. */
    suspend fun update(): Result<ProviderDatabaseInfo>
}

/** Owned by intelligence/. */
interface RewardAssistantRepository {
    fun observeGames(): Flow<List<GameRewardProfile>>
    suspend fun setBehaviour(packageName: String, behaviour: GameAdBehaviour)
}

/** Owned by vpn/ (write side + read side). */
interface StatsRepository {
    fun observeDashboard(): Flow<DashboardStats>
    fun observeDaily(days: Int): Flow<List<DailyPoint>>
    fun observeActivity(packageName: String?, providerId: String?, blockedOnly: Boolean, limit: Int = 500): Flow<List<ActivityLogEntity>>
    fun observeActivityPackages(): Flow<List<String>>
    fun observeActivityProviders(): Flow<List<String>>
    suspend fun clearActivity()
    suspend fun clearStatistics()
}
