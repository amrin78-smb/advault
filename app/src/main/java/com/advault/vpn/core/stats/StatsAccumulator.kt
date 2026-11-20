package com.advault.vpn.core.stats

import com.advault.core.common.Decision
import com.advault.core.common.FilterCategory
import com.advault.core.database.ActivityLogEntity
import com.advault.core.database.DailyStatsEntity
import com.advault.data.models.DailyPoint
import com.advault.vpn.core.QueryEvent

/** Delta to add into one daily_stats row. */
data class DailyDelta(
    val day: Long,
    val packageName: String,
    val total: Long,
    val blocked: Long,
    val ads: Long,
    val tracking: Long,
    val bytes: Long,
)

class StatsBatch(val daily: List<DailyDelta>, val logs: List<ActivityLogEntity>) {
    val isEmpty: Boolean get() = daily.isEmpty() && logs.isEmpty()
}

/**
 * In-memory accumulation of query events between Room flushes (no per-query disk writes).
 * Thread-safe. Pending activity log entries are bounded so a stalled flush cannot exhaust memory.
 */
class StatsAccumulator(private val maxPendingLogs: Int = 5_000) {

    private class Counter { var total = 0L; var blocked = 0L; var ads = 0L; var tracking = 0L; var bytes = 0L }

    private val daily = HashMap<Pair<Long, String>, Counter>()
    private val logs = ArrayDeque<ActivityLogEntity>()
    private var events = 0
    var droppedLogs = 0L
        private set

    /** Number of events recorded since the last [drain]. */
    val pendingEvents: Int @Synchronized get() = events

    @Synchronized
    fun record(e: QueryEvent, logActivity: Boolean) {
        events++
        val key = dayOf(e.timestamp) to (e.packageName ?: "")
        val c = daily.getOrPut(key) { Counter() }
        c.total++
        c.bytes += e.bytes
        if (e.blocked) {
            c.blocked++
            when (categoryGroup(e.category)) {
                Group.ADS -> c.ads++
                Group.TRACKING -> c.tracking++
                Group.NONE -> {}
            }
        }
        if (logActivity) {
            if (logs.size >= maxPendingLogs) { logs.removeFirst(); droppedLogs++ }
            logs.addLast(
                ActivityLogEntity(
                    timestamp = e.timestamp, uid = e.uid, packageName = e.packageName, domain = e.domain,
                    queryType = e.queryType, decision = if (e.blocked) Decision.BLOCKED else Decision.ALLOWED,
                    reason = e.reason, category = e.category, providerId = e.providerId, listId = e.listId,
                    latencyMs = e.latencyMs, cached = e.cached,
                )
            )
        }
    }

    @Synchronized
    fun drain(): StatsBatch {
        val d = daily.map { (k, c) -> DailyDelta(k.first, k.second, c.total, c.blocked, c.ads, c.tracking, c.bytes) }
        val l = logs.toList()
        daily.clear(); logs.clear(); events = 0
        return StatsBatch(d, l)
    }

    /** Puts a batch back after a failed flush (merged with anything recorded meanwhile). */
    @Synchronized
    fun restore(batch: StatsBatch) {
        for (d in batch.daily) {
            val c = daily.getOrPut(d.day to d.packageName) { Counter() }
            c.total += d.total; c.blocked += d.blocked; c.ads += d.ads; c.tracking += d.tracking; c.bytes += d.bytes
        }
        for (entry in batch.logs.asReversed()) {
            if (logs.size >= maxPendingLogs) { droppedLogs++; continue }
            logs.addFirst(entry)
        }
    }

    @Synchronized fun discardLogs() { logs.clear() }
    @Synchronized fun discardAll() { daily.clear(); logs.clear(); events = 0 }

    enum class Group { ADS, TRACKING, NONE }

    companion object {
        const val DAY_MS = 86_400_000L

        /** Days since epoch, UTC (matches DailyStatsEntity.day). */
        fun dayOf(epochMs: Long): Long = Math.floorDiv(epochMs, DAY_MS)

        fun categoryGroup(c: FilterCategory?): Group = when (c) {
            FilterCategory.ADVERTISING, FilterCategory.GAME_ADS, FilterCategory.MALICIOUS -> Group.ADS
            FilterCategory.TRACKING, FilterCategory.ANALYTICS, FilterCategory.TELEMETRY -> Group.TRACKING
            null -> Group.NONE
        }

        /** Sums per-package rows into one point per day for [days] days ending [today], zero-filling gaps. */
        fun dailySeries(rows: List<DailyStatsEntity>, today: Long, days: Int): List<DailyPoint> {
            val n = days.coerceAtLeast(1)
            val first = today - n + 1
            val totals = LongArray(n)
            val blocked = LongArray(n)
            for (r in rows) {
                val i = (r.day - first).toInt()
                if (i in 0 until n) { totals[i] += r.totalQueries; blocked[i] += r.blockedQueries }
            }
            return (0 until n).map { DailyPoint(first + it, totals[it], blocked[it]) }
        }

        fun estimatedBytesSaved(adsBlocked: Long, trackingBlocked: Long, bytesPerRequest: Int): Long =
            (adsBlocked + trackingBlocked) * bytesPerRequest.coerceAtLeast(0).toLong()
    }
}
