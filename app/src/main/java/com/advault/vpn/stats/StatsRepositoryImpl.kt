package com.advault.vpn.stats

import com.advault.core.common.AppProtection
import com.advault.core.database.ActivityLogDao
import com.advault.core.database.ActivityLogEntity
import com.advault.core.database.BlocklistDao
import com.advault.core.database.DailyStatsDao
import com.advault.core.preferences.SettingsRepository
import com.advault.data.models.DailyPoint
import com.advault.data.models.DashboardStats
import com.advault.data.repository.AppRepository
import com.advault.data.repository.StatsRepository
import com.advault.vpn.core.stats.StatsAccumulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All figures come from real observed DNS traffic (daily_stats rows written by the VPN), except
 * [DashboardStats.estimatedBytesSaved], which is an explicitly labelled estimate.
 * Values lag live traffic by at most one flush interval (~5 s).
 */
@Singleton
class StatsRepositoryImpl @Inject constructor(
    private val dailyDao: DailyStatsDao,
    private val activityDao: ActivityLogDao,
    private val blocklistDao: BlocklistDao,
    private val apps: AppRepository,
    private val settings: SettingsRepository,
    private val recorder: StatsRecorder,
    private val clock: Clock,
) : StatsRepository {

    override fun observeDashboard(): Flow<DashboardStats> {
        val protectedApps = apps.observeApps()
            .map { list -> list.count { it.protection != AppProtection.EXCLUDED } }
            .onStart { emit(0) }
            .catch { emit(0) }
            .distinctUntilChanged()
        val lastUpdate = blocklistDao.observeAll()
            .map { lists -> lists.maxOfOrNull { it.lastUpdated } ?: 0L }
            .distinctUntilChanged()
        val bytesPer = settings.settings.map { it.estimatedBytesPerBlockedRequest }.distinctUntilChanged()
        return combine(dailyDao.observeTotals(0), bytesPer, protectedApps, lastUpdate) { t, bpr, prot, last ->
            DashboardStats(
                totalQueries = t.totalQueries,
                blockedQueries = t.blockedQueries,
                adsBlocked = t.adsBlocked,
                trackingBlocked = t.trackingBlocked,
                measuredDnsBytes = t.dnsBytes,
                estimatedBytesSaved = StatsAccumulator.estimatedBytesSaved(t.adsBlocked, t.trackingBlocked, bpr),
                protectedApps = prot,
                lastBlocklistUpdate = last,
            )
        }
    }

    override fun observeDaily(days: Int): Flow<List<DailyPoint>> {
        val n = days.coerceIn(1, 3650)
        val today = StatsAccumulator.dayOf(clock.now())
        return dailyDao.observeDaily(today - n + 1).map { StatsAccumulator.dailySeries(it, today, n) }
    }

    override fun observeActivity(packageName: String?, providerId: String?, blockedOnly: Boolean, limit: Int): Flow<List<ActivityLogEntity>> =
        activityDao.observe(packageName, providerId, blockedOnly, limit)

    override fun observeActivityPackages(): Flow<List<String>> = activityDao.observePackages()
    override fun observeActivityProviders(): Flow<List<String>> = activityDao.observeProviders()

    override suspend fun clearActivity() {
        recorder.recorder.accumulator.discardLogs()
        activityDao.clear()
    }

    override suspend fun clearStatistics() {
        recorder.recorder.accumulator.discardAll()
        dailyDao.clear()
    }
}

/** Injectable wall clock (tests can substitute). */
fun interface Clock {
    fun now(): Long

    companion object { val SYSTEM = Clock { System.currentTimeMillis() } }
}
