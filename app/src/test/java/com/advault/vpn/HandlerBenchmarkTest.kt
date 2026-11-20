package com.advault.vpn

import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.data.models.FilterEngineStatus
import com.advault.data.models.FilterVerdict
import com.advault.data.repository.FilterEngine
import com.advault.vpn.core.DnsQueryHandler
import com.advault.vpn.core.PacketWriter
import com.advault.vpn.core.ProviderClassifier
import com.advault.vpn.core.QueryRecorder
import com.advault.vpn.core.UidResolver
import com.advault.vpn.core.dns.DnsCache
import com.advault.vpn.core.stats.StatsAccumulator
import com.advault.vpn.core.upstream.Upstream
import com.advault.vpn.core.upstream.UpstreamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Micro-benchmark of the per-packet hot path (IP parse, DNS parse, policy, filter, response build, checksum,
 * stats accumulation) with a synthetic 100k-domain suffix-matching filter. Prints queries/second.
 */
class HandlerBenchmarkTest {

    /** HashSet suffix matcher: representative of a hashed/trie lookup cost. */
    private class SyntheticFilter(private val domains: Set<String>) : FilterEngine {
        override val status = MutableStateFlow(FilterEngineStatus(loaded = true, totalDomains = domains.size))
        override fun evaluate(domain: String, mode: FilterMode): FilterVerdict {
            var d = domain
            while (true) {
                if (d in domains) return FilterVerdict(true, DecisionReason.BLOCKLIST, FilterCategory.ADVERTISING, d)
                val i = d.indexOf('.')
                if (i < 0 || d.indexOf('.', i + 1) < 0) return FilterVerdict(false, DecisionReason.NO_MATCH)
                d = d.substring(i + 1)
            }
        }
        override suspend fun reload() {}
    }

    @Test fun throughput() {
        val rules = HashSet<String>(200_000)
        for (i in 0 until 100_000) rules += "ad$i.tracker${i % 997}.com"
        val filter = SyntheticFilter(rules)
        val written = AtomicLong()
        val acc = StatsAccumulator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val upstream = object : Upstream {
            override val description = "instant"
            override fun resolve(query: ByteArray, timeoutMs: Int) = UpstreamResult(TestDns.answer(query, listOf(byteArrayOf(1, 2, 3, 4))), "i", 0)
        }
        val h = DnsQueryHandler(
            listOf(TestDns.VDNS_V4), filter, MapPolicy(emptyMap()), ProviderClassifier.NONE,
            UidResolver { _, _, _, _, _ -> 10_001 }, QueryRecorder { acc.record(it, true); if (acc.pendingEvents > 5000) acc.drain() },
            PacketWriter { written.incrementAndGet() }, DnsCache(maxEntries = 4096), { upstream }, scope,
        )
        val blockedPkts = (0 until 2000).map { TestDns.udpV4(TestDns.query("sub.ad${it * 37}.tracker${(it * 37) % 997}.com", id = it)) }
        val allowedPkts = (0 until 2000).map { TestDns.udpV4(TestDns.query("www.site$it.example.org", id = it)) }
        // Warm the cache for allowed names.
        allowedPkts.forEach { h.handlePacket(it, it.size) }
        val deadline = System.currentTimeMillis() + 10_000
        while (h.inFlightCount > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(50)

        fun run(label: String, pkts: List<ByteArray>, iterations: Int): Double {
            repeat(iterations / 10) { pkts[it % pkts.size].let { p -> h.handlePacket(p, p.size) } } // JIT warm-up
            val start = System.nanoTime()
            for (i in 0 until iterations) { val p = pkts[i % pkts.size]; h.handlePacket(p, p.size) }
            val secs = (System.nanoTime() - start) / 1e9
            val qps = iterations / secs
            println("BENCHMARK %-28s %,10.0f queries/s  (%.2f µs/query, %d iterations)".format(label, qps, secs * 1e6 / iterations, iterations))
            return qps
        }
        val blockedQps = run("blocked (null-IP synth)", blockedPkts, 200_000)
        val cachedQps = run("allowed (cache hit)", allowedPkts, 200_000)
        val mixed = blockedPkts.zip(allowedPkts).flatMap { listOf(it.first, it.second) }
        val mixedQps = run("mixed 50/50", mixed, 200_000)
        scope.cancel()
        // Very conservative floor so CI noise never fails the build; real numbers are printed above.
        assertTrue(blockedQps > 5_000 && cachedQps > 5_000 && mixedQps > 5_000)
    }
}
