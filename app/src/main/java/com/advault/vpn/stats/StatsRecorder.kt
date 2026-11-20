package com.advault.vpn.stats

import android.util.Log
import androidx.room.withTransaction
import com.advault.core.database.ActivityLogDao
import com.advault.core.database.AppDatabase
import com.advault.core.database.DailyStatsDao
import com.advault.core.preferences.SettingsRepository
import com.advault.vpn.core.QueryEvent
import com.advault.vpn.core.QueryRecorder
import com.advault.vpn.core.stats.StatsAccumulator
import com.advault.vpn.core.stats.StatsBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Abstraction over Room so the flush logic can be unit tested. */
interface StatsSink {
    suspend fun write(batch: StatsBatch)
    suspend fun purgeLogsBefore(epochMs: Long)
}

class RoomStatsSink(
    private val db: AppDatabase,
    private val dailyDao: DailyStatsDao,
    private val activityDao: ActivityLogDao,
) : StatsSink {
    override suspend fun write(batch: StatsBatch) {
        db.withTransaction {
            for (d in batch.daily) dailyDao.add(d.day, d.packageName, d.total, d.blocked, d.ads, d.tracking, d.bytes)
            if (batch.logs.isNotEmpty()) activityDao.insertAll(batch.logs)
        }
    }

    override suspend fun purgeLogsBefore(epochMs: Long) = activityDao.deleteOlderThan(epochMs)
}

/**
 * Batching recorder: query events accumulate in memory and are flushed to Room every [FLUSH_INTERVAL_MS]
 * or after [FLUSH_EVENTS] events, never per query. Activity log rows are only kept when activity logging
 * is enabled. Old log rows are purged according to logRetentionDays.
 */
class BatchingStatsRecorder(
    private val sink: StatsSink,
    val accumulator: StatsAccumulator = StatsAccumulator(),
    private val clock: () -> Long = System::currentTimeMillis,
) : QueryRecorder {
    @Volatile var loggingEnabled: Boolean = true
    @Volatile var retentionDays: Int = 7

    private val flushRequests = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()

    override fun record(event: QueryEvent) {
        accumulator.record(event, loggingEnabled)
        if (accumulator.pendingEvents >= FLUSH_EVENTS) flushRequests.trySend(Unit)
    }

    /** Runs the periodic flush and purge loops in [scope] until it is cancelled. */
    fun launchIn(scope: CoroutineScope): Job = scope.launch {
        launch {
            while (isActive) {
                withTimeoutOrNull(FLUSH_INTERVAL_MS) { flushRequests.receive() }
                flush()
            }
        }
        launch {
            while (isActive) {
                purge()
                delay(PURGE_INTERVAL_MS)
            }
        }
    }

    /** Writes everything pending. On failure the batch is put back for the next attempt. */
    suspend fun flush() = mutex.withLock {
        val batch = accumulator.drain()
        if (batch.isEmpty) return@withLock
        try {
            sink.write(batch)
        } catch (e: Exception) {
            Log.w(TAG, "Stats flush failed; will retry", e)
            accumulator.restore(batch)
        }
    }

    suspend fun purge() {
        try {
            sink.purgeLogsBefore(clock() - retentionDays.coerceAtLeast(1) * StatsAccumulator.DAY_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Log purge failed", e)
        }
    }

    companion object {
        const val TAG = "AdVaultStats"
        const val FLUSH_INTERVAL_MS = 5_000L
        const val FLUSH_EVENTS = 200
        const val PURGE_INTERVAL_MS = 60 * 60 * 1000L
    }
}

/** Process-wide recorder shared by the VPN service (write side) and [StatsRepositoryImpl] (clear functions). */
@Singleton
class StatsRecorder @Inject constructor(
    db: AppDatabase,
    dailyDao: DailyStatsDao,
    activityDao: ActivityLogDao,
    private val settings: SettingsRepository,
) {
    val recorder = BatchingStatsRecorder(RoomStatsSink(db, dailyDao, activityDao))

    /** Starts flushing and follows the logging/retention settings. Cancel the returned job to stop. */
    fun launchIn(scope: CoroutineScope): Job = scope.launch {
        launch {
            settings.settings.collect {
                recorder.loggingEnabled = it.activityLoggingEnabled
                recorder.retentionDays = it.logRetentionDays
                if (!it.activityLoggingEnabled) recorder.accumulator.discardLogs()
            }
        }
        recorder.launchIn(this)
    }

    suspend fun flush() = recorder.flush()
}
