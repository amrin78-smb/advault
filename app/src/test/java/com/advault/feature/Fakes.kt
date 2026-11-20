package com.advault.feature

import android.content.Intent
import com.advault.core.common.AppProtection
import com.advault.core.common.FilterMode
import com.advault.core.database.ActivityLogEntity
import com.advault.core.preferences.AppSettings
import com.advault.data.models.AdProvider
import com.advault.data.models.DailyPoint
import com.advault.data.models.DashboardStats
import com.advault.data.models.InstalledApp
import com.advault.data.models.ProtectionState
import com.advault.data.models.ProviderDatabaseInfo
import com.advault.data.models.ProviderMatch
import com.advault.data.models.VpnDiagnostics
import com.advault.data.repository.AppRepository
import com.advault.data.repository.ProtectionController
import com.advault.data.repository.ProviderRepository
import com.advault.data.repository.StatsRepository
import com.advault.feature.common.SettingsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeProtectionController : ProtectionController {
    override val state = MutableStateFlow<ProtectionState>(ProtectionState.Stopped)
    override val diagnostics = MutableStateFlow(VpnDiagnostics())
    var consentIntent: Intent? = null
    var startCalls = 0
    var stopCalls = 0
    var reloadCalls = 0
    var refreshCalls = 0
    override fun prepareIntent(): Intent? = consentIntent
    override fun start() { startCalls++ }
    override fun stop() { stopCalls++ }
    override fun reload() { reloadCalls++ }
    override fun refreshDiagnostics() { refreshCalls++ }
}

class FakeStatsRepository : StatsRepository {
    val dashboard = MutableStateFlow(DashboardStats())
    val daily = MutableStateFlow<List<DailyPoint>>(emptyList())
    val activity = MutableStateFlow<List<ActivityLogEntity>>(emptyList())
    val packages = MutableStateFlow<List<String>>(emptyList())
    val providers = MutableStateFlow<List<String>>(emptyList())
    val activityQueries = mutableListOf<Triple<String?, String?, Boolean>>()
    var clearedActivity = 0

    override fun observeDashboard(): Flow<DashboardStats> = dashboard
    override fun observeDaily(days: Int): Flow<List<DailyPoint>> = daily
    override fun observeActivity(packageName: String?, providerId: String?, blockedOnly: Boolean, limit: Int): Flow<List<ActivityLogEntity>> {
        activityQueries += Triple(packageName, providerId, blockedOnly)
        return activity.map { list ->
            list.filter { e ->
                (packageName == null || e.packageName == packageName) &&
                    (providerId == null || e.providerId == providerId) &&
                    (!blockedOnly || e.decision == com.advault.core.common.Decision.BLOCKED)
            }.take(limit)
        }
    }
    override fun observeActivityPackages(): Flow<List<String>> = packages
    override fun observeActivityProviders(): Flow<List<String>> = providers
    override suspend fun clearActivity() { clearedActivity++; activity.value = emptyList() }
    override suspend fun clearStatistics() { dashboard.value = DashboardStats() }
}

class FakeAppRepository(initial: List<InstalledApp> = emptyList()) : AppRepository {
    val apps = MutableStateFlow(initial)
    val protectionCalls = mutableListOf<Pair<String, AppProtection>>()
    override fun observeApps(): Flow<List<InstalledApp>> = apps
    override suspend fun setProtection(packageName: String, protection: AppProtection) {
        protectionCalls += packageName to protection
        apps.value = apps.value.map { if (it.packageName == packageName) it.copy(protection = protection) else it }
    }
    override suspend fun excludedPackages(): Set<String> = apps.value.filter { it.protection == AppProtection.EXCLUDED }.map { it.packageName }.toSet()
    override fun modeForUid(uid: Int): FilterMode? = FilterMode.AGGRESSIVE
    override fun packageForUid(uid: Int): String? = null
    override suspend fun refresh() {}
}

class FakeProviderRepository(initial: List<AdProvider> = emptyList()) : ProviderRepository {
    override val providers = MutableStateFlow(initial)
    override val info = MutableStateFlow<ProviderDatabaseInfo?>(null)
    override fun match(domain: String): ProviderMatch? = null
    override suspend fun load() {}
    override suspend fun update(): Result<ProviderDatabaseInfo> = Result.failure(IllegalStateException("offline"))
}

class FakeSettingsSource(initial: AppSettings = AppSettings()) : SettingsSource {
    val flow = MutableStateFlow(initial)
    override fun settings(): Flow<AppSettings> = flow
}

fun app(
    pkg: String,
    label: String = pkg.substringAfterLast('.'),
    system: Boolean = false,
    game: Boolean = false,
    protection: AppProtection = AppProtection.DEFAULT,
    queries: Long = 0,
    blocked: Long = 0,
    sdks: List<String> = emptyList(),
) = InstalledApp(pkg, label, 10_000, system, game, null, protection, sdks, queries, blocked, 0)

fun provider(id: String, name: String) = AdProvider(id, name, "", "2026-01-01", emptyList(), emptyList(), false)
