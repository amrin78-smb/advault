package com.advault.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.advault.core.common.RuleType
import kotlinx.coroutines.flow.Flow

@Dao
interface BlocklistDao {
    @Query("SELECT * FROM blocklists ORDER BY builtIn DESC, name") fun observeAll(): Flow<List<BlocklistEntity>>
    @Query("SELECT * FROM blocklists") suspend fun getAll(): List<BlocklistEntity>
    @Query("SELECT * FROM blocklists WHERE id = :id") suspend fun get(id: String): BlocklistEntity?
    @Upsert suspend fun upsert(entity: BlocklistEntity)
    @Upsert suspend fun upsertAll(entities: List<BlocklistEntity>)
    @Query("UPDATE blocklists SET enabled = :enabled WHERE id = :id") suspend fun setEnabled(id: String, enabled: Boolean)
    @Query("DELETE FROM blocklists WHERE id = :id AND builtIn = 0") suspend fun deleteCustom(id: String)
}

@Dao
interface CustomRuleDao {
    @Query("SELECT * FROM custom_rules ORDER BY createdAt DESC") fun observeAll(): Flow<List<CustomRuleEntity>>
    @Query("SELECT * FROM custom_rules") suspend fun getAll(): List<CustomRuleEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(rule: CustomRuleEntity): Long
    @Query("DELETE FROM custom_rules WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM custom_rules WHERE domain = :domain AND type = :type") suspend fun delete(domain: String, type: RuleType)
}

@Dao
interface AppPolicyDao {
    @Query("SELECT * FROM app_policies") fun observeAll(): Flow<List<AppPolicyEntity>>
    @Query("SELECT * FROM app_policies") suspend fun getAll(): List<AppPolicyEntity>
    @Upsert suspend fun upsert(entity: AppPolicyEntity)
    @Query("DELETE FROM app_policies WHERE packageName = :pkg") suspend fun delete(pkg: String)
}

@Dao
interface ActivityLogDao {
    @Insert suspend fun insertAll(entries: List<ActivityLogEntity>)

    @Query(
        """SELECT * FROM activity_log
           WHERE (:pkg IS NULL OR packageName = :pkg)
             AND (:providerId IS NULL OR providerId = :providerId)
             AND (:blockedOnly = 0 OR decision = 'BLOCKED')
           ORDER BY timestamp DESC LIMIT :limit"""
    )
    fun observe(pkg: String?, providerId: String?, blockedOnly: Boolean, limit: Int): Flow<List<ActivityLogEntity>>

    @Query("SELECT * FROM activity_log WHERE packageName = :pkg AND providerId IS NOT NULL ORDER BY timestamp DESC LIMIT :limit")
    suspend fun providerActivityFor(pkg: String, limit: Int): List<ActivityLogEntity>

    @Query("SELECT DISTINCT packageName FROM activity_log WHERE packageName IS NOT NULL") fun observePackages(): Flow<List<String>>
    @Query("SELECT DISTINCT providerId FROM activity_log WHERE providerId IS NOT NULL") fun observeProviders(): Flow<List<String>>
    @Query("DELETE FROM activity_log WHERE timestamp < :before") suspend fun deleteOlderThan(before: Long)
    @Query("DELETE FROM activity_log") suspend fun clear()
}

/** Per-package/per-provider observation aggregate for the Reward Assistant. */
data class ProviderObservation(
    val packageName: String,
    val providerId: String,
    val hits: Long,
    val blocked: Long,
    val lastSeen: Long,
)

@Dao
interface ProviderObservationDao {
    @Query(
        """SELECT packageName, providerId, COUNT(*) AS hits,
                  SUM(CASE WHEN decision = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked,
                  MAX(timestamp) AS lastSeen
           FROM activity_log WHERE packageName IS NOT NULL AND providerId IS NOT NULL
           GROUP BY packageName, providerId"""
    )
    fun observeAll(): Flow<List<ProviderObservation>>
}

data class StatsTotals(
    val totalQueries: Long,
    val blockedQueries: Long,
    val adsBlocked: Long,
    val trackingBlocked: Long,
    val dnsBytes: Long,
)

data class PackageTotals(
    val packageName: String,
    val totalQueries: Long,
    val blockedQueries: Long,
    val dnsBytes: Long,
)

@Dao
interface DailyStatsDao {
    /** Atomically adds deltas into a row (creating it if needed). */
    @Query(
        """INSERT INTO daily_stats (day, packageName, totalQueries, blockedQueries, adsBlocked, trackingBlocked, dnsBytes)
           VALUES (:day, :pkg, :total, :blocked, :ads, :tracking, :bytes)
           ON CONFLICT(day, packageName) DO UPDATE SET
             totalQueries = totalQueries + :total,
             blockedQueries = blockedQueries + :blocked,
             adsBlocked = adsBlocked + :ads,
             trackingBlocked = trackingBlocked + :tracking,
             dnsBytes = dnsBytes + :bytes"""
    )
    suspend fun add(day: Long, pkg: String, total: Long, blocked: Long, ads: Long, tracking: Long, bytes: Long)

    @Query(
        """SELECT COALESCE(SUM(totalQueries),0) AS totalQueries, COALESCE(SUM(blockedQueries),0) AS blockedQueries,
                  COALESCE(SUM(adsBlocked),0) AS adsBlocked, COALESCE(SUM(trackingBlocked),0) AS trackingBlocked,
                  COALESCE(SUM(dnsBytes),0) AS dnsBytes
           FROM daily_stats WHERE day >= :sinceDay"""
    )
    fun observeTotals(sinceDay: Long): Flow<StatsTotals>

    @Query(
        """SELECT packageName, SUM(totalQueries) AS totalQueries, SUM(blockedQueries) AS blockedQueries, SUM(dnsBytes) AS dnsBytes
           FROM daily_stats WHERE day >= :sinceDay GROUP BY packageName"""
    )
    fun observePerPackage(sinceDay: Long): Flow<List<PackageTotals>>

    @Query("SELECT * FROM daily_stats WHERE day >= :sinceDay ORDER BY day") fun observeDaily(sinceDay: Long): Flow<List<DailyStatsEntity>>
    @Query("DELETE FROM daily_stats") suspend fun clear()
}
