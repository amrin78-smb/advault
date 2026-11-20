package com.advault.vpn

import com.advault.core.common.Decision
import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.database.DailyStatsEntity
import com.advault.vpn.core.QueryEvent
import com.advault.vpn.core.stats.StatsAccumulator
import com.advault.vpn.core.stats.StatsBatch
import com.advault.vpn.stats.BatchingStatsRecorder
import com.advault.vpn.stats.StatsSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    private val day = 20_000L
    private val t0 = day * StatsAccumulator.DAY_MS + 1000

    private fun ev(pkg: String?, blocked: Boolean, cat: FilterCategory? = null, ts: Long = t0, bytes: Long = 100) = QueryEvent(
        ts, 10, pkg, "d.example", 1, blocked, if (blocked) DecisionReason.BLOCKLIST else DecisionReason.NO_MATCH,
        cat, null, null, 0, false, bytes,
    )

    @Test fun aggregatesPerDayAndPackageWithAdsTrackingSplit() {
        val acc = StatsAccumulator()
        acc.record(ev("a", true, FilterCategory.ADVERTISING), true)
        acc.record(ev("a", true, FilterCategory.GAME_ADS), true)
        acc.record(ev("a", true, FilterCategory.MALICIOUS), true)
        acc.record(ev("a", true, FilterCategory.TRACKING), true)
        acc.record(ev("a", true, FilterCategory.ANALYTICS), true)
        acc.record(ev("a", true, FilterCategory.TELEMETRY), true)
        acc.record(ev("a", true, null), true) // custom block rule without category
        acc.record(ev("a", false), true)
        acc.record(ev(null, false), false)
        acc.record(ev("a", false, ts = t0 + StatsAccumulator.DAY_MS), true)
        assertEquals(10, acc.pendingEvents)
        val b = acc.drain()
        val a0 = b.daily.single { it.packageName == "a" && it.day == day }
        assertEquals(8, a0.total); assertEquals(7, a0.blocked); assertEquals(3, a0.ads); assertEquals(3, a0.tracking); assertEquals(800, a0.bytes)
        assertEquals(1, b.daily.single { it.packageName == "" }.total)
        assertEquals(1, b.daily.single { it.day == day + 1 }.total)
        assertEquals(9, b.logs.size) // one event recorded with logging off
        assertEquals(Decision.BLOCKED, b.logs.first().decision)
        assertEquals(0, acc.pendingEvents)
        assertTrue(acc.drain().isEmpty)
    }

    @Test fun pendingLogsAreBounded() {
        val acc = StatsAccumulator(maxPendingLogs = 10)
        repeat(25) { acc.record(ev("a", false), true) }
        val b = acc.drain()
        assertEquals(10, b.logs.size); assertEquals(15, acc.droppedLogs)
        assertEquals(25, b.daily.single().total)
    }

    @Test fun dailySeriesZeroFillsAndSumsPackages() {
        val rows = listOf(
            DailyStatsEntity(day, "a", 10, 3), DailyStatsEntity(day, "b", 5, 1),
            DailyStatsEntity(day - 2, "a", 7, 7), DailyStatsEntity(day - 30, "a", 1000, 1000),
        )
        val s = StatsAccumulator.dailySeries(rows, today = day, days = 3)
        assertEquals(listOf(day - 2, day - 1, day), s.map { it.day })
        assertEquals(listOf(7L, 0L, 15L), s.map { it.total })
        assertEquals(listOf(7L, 0L, 4L), s.map { it.blocked })
    }

    @Test fun estimatedBytesSaved() {
        assertEquals(30 * 25_000L, StatsAccumulator.estimatedBytesSaved(20, 10, 25_000))
        assertEquals(0L, StatsAccumulator.estimatedBytesSaved(20, 10, -5))
    }

    @Test fun dayOfIsUtcDays() {
        assertEquals(0L, StatsAccumulator.dayOf(0)); assertEquals(0L, StatsAccumulator.dayOf(StatsAccumulator.DAY_MS - 1))
        assertEquals(1L, StatsAccumulator.dayOf(StatsAccumulator.DAY_MS)); assertEquals(-1L, StatsAccumulator.dayOf(-1))
    }

    private class FakeSink(var fail: Boolean = false) : StatsSink {
        val batches = ArrayList<StatsBatch>()
        var purgedBefore = 0L
        override suspend fun write(batch: StatsBatch) { if (fail) error("db down"); batches += batch }
        override suspend fun purgeLogsBefore(epochMs: Long) { purgedBefore = epochMs }
    }

    @Test fun recorderBatchesAndRetriesFailedFlush() = runTest {
        val sink = FakeSink(fail = true)
        val rec = BatchingStatsRecorder(sink, clock = { t0 })
        repeat(5) { rec.record(ev("a", it % 2 == 0, FilterCategory.ADVERTISING)) }
        assertTrue("no writes before flush", sink.batches.isEmpty())
        rec.flush() // fails -> restored
        assertEquals("restored after failed flush", 5, rec.accumulator.pendingEvents)
        rec.record(ev("a", false))
        sink.fail = false
        rec.flush()
        val d = sink.batches.single().daily.single()
        assertEquals(6, d.total); assertEquals(3, d.blocked); assertEquals(3, d.ads)
        assertEquals(6, sink.batches.single().logs.size)
        rec.flush()
        assertEquals(1, sink.batches.size) // nothing pending -> no write

        rec.loggingEnabled = false
        rec.record(ev("a", true, FilterCategory.TRACKING))
        rec.flush()
        assertEquals(0, sink.batches[1].logs.size); assertEquals(1, sink.batches[1].daily.single().tracking)

        rec.retentionDays = 7
        rec.purge()
        assertEquals(t0 - 7 * StatsAccumulator.DAY_MS, sink.purgedBefore)
    }
}
