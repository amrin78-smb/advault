package com.advault.filtering

import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.filtering.engine.FilterSnapshot
import com.advault.filtering.engine.FilterSnapshotBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Synthetic benchmark: 250k realistic domains over 8 lists / 6 categories.
 * Bounds are deliberately loose so the test is stable on slow CI machines; the printed numbers are
 * what matter.
 */
class MatcherBenchmarkTest {

    private val tlds = arrayOf("com", "net", "org", "io", "co.uk", "de", "ru", "info", "xyz", "app", "com.br", "jp")
    private val words = arrayOf("ads", "track", "cdn", "pixel", "metrics", "api", "static", "img", "media", "srv",
        "click", "stats", "beacon", "edge", "log", "sdk", "tag", "sync", "bid", "rtb", "ssp", "dsp", "event")

    private fun randomLabel(r: Random): String {
        val len = 4 + r.nextInt(10)
        val sb = StringBuilder(len)
        repeat(len) { sb.append(('a' + r.nextInt(26))) }
        if (r.nextInt(5) == 0) sb.append(r.nextInt(100))
        return sb.toString()
    }

    private fun randomDomain(r: Random): String {
        val base = randomLabel(r) + "." + tlds[r.nextInt(tlds.size)]
        return when (r.nextInt(4)) {
            0 -> base
            1 -> words[r.nextInt(words.size)] + "." + base
            2 -> words[r.nextInt(words.size)] + r.nextInt(50) + "." + randomLabel(r) + "." + base
            else -> randomLabel(r) + "-" + words[r.nextInt(words.size)] + "." + base
        }
    }

    @Test fun benchmark250k() {
        val r = Random(42)
        val domains = Array(250_000) { randomDomain(r) }
        val misses = Array(100_000) { randomDomain(r) + ".zz" } // unlisted TLD: guaranteed misses
        val hitQueries = Array(100_000) { i -> if (i % 2 == 0) domains[r.nextInt(domains.size)] else "x1.y2." + domains[r.nextInt(domains.size)] }
        val all = FilterSnapshot.categoryMask(FilterCategory.entries)

        System.gc(); Thread.sleep(100); System.gc()
        val rt = Runtime.getRuntime()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val b = FilterSnapshotBuilder(250_000)
        val bits = LongArray(8) { b.source("list$it", FilterCategory.entries[it % 6]) }
        domains.forEachIndexed { i, d -> b.addListRule(d, bits[i % 8] or if (i % 5 == 0) bits[(i + 3) % 8] else 0L) }
        val snap = b.build()
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        System.gc(); Thread.sleep(100); System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val retainedMb = (after - before) / (1024.0 * 1024.0)
        val arraysMb = snap.lists.memoryBytes / (1024.0 * 1024.0)

        val unique = domains.toHashSet().size
        assertEquals(unique, snap.totalDomains)
        for (d in domains) assertTrue(snap.evaluate(d, FilterMode.AGGRESSIVE, all).blocked)

        fun measure(q: Array<String>, rounds: Int): Pair<Double, Int> {
            var hits = 0
            val t = System.nanoTime()
            repeat(rounds) { for (s in q) if (snap.lists.unionMask(s) != 0L) hits++ }
            return (q.size.toLong() * rounds) / ((System.nanoTime() - t) / 1e9) to hits
        }
        fun measureEvaluate(q: Array<String>, rounds: Int): Double {
            var hits = 0
            val t = System.nanoTime()
            repeat(rounds) { for (s in q) if (snap.evaluate(s, FilterMode.AGGRESSIVE, all).blocked) hits++ }
            check(hits >= 0)
            return (q.size.toLong() * rounds) / ((System.nanoTime() - t) / 1e9)
        }
        measure(hitQueries, 5); measure(misses, 5); measureEvaluate(hitQueries, 3) // warm-up / JIT
        val (hitRate, hitCount) = measure(hitQueries, 20)
        val (missRate, missCount) = measure(misses, 20)
        val evalHit = measureEvaluate(hitQueries, 10)
        val evalMiss = measureEvaluate(misses, 10)

        println(
            "BENCHMARK 250k: unique=$unique build=${buildMs}ms arrays=${"%.1f".format(arraysMb)}MB " +
                "retained≈${"%.1f".format(retainedMb)}MB | matcher hits ${"%.1f".format(hitRate / 1e6)}M/s, " +
                "misses ${"%.1f".format(missRate / 1e6)}M/s | evaluate() hits ${"%.1f".format(evalHit / 1e6)}M/s, " +
                "misses ${"%.1f".format(evalMiss / 1e6)}M/s",
        )
        assertEquals(100_000 * 20, hitCount)
        assertTrue("unexpected misses matched: $missCount", missCount == 0)
        assertTrue("arrays too large: $arraysMb MB", arraysMb < 20)
        assertTrue("build too slow: $buildMs ms", buildMs < 10_000)
        // Loose floors (shared/loaded CI machines); typical desktop results are several M/s, see output.
        assertTrue("hit lookups too slow: $hitRate/s", hitRate > 500_000)
        assertTrue("miss lookups too slow: $missRate/s", missRate > 500_000)
        assertTrue("evaluate too slow: $evalHit/s", evalHit > 250_000)
    }
}
