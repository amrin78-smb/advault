package com.advault.vpn

import com.advault.core.common.BlockResponse
import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.vpn.core.DnsQueryHandler
import com.advault.vpn.core.HandlerConfig
import com.advault.vpn.core.ProviderClassifier
import com.advault.vpn.core.QueryEvent
import com.advault.vpn.core.QueryRecorder
import com.advault.vpn.core.RepositoryAppPolicy
import com.advault.vpn.core.UidResolver
import com.advault.vpn.core.dns.DnsCache
import com.advault.vpn.core.dns.DnsParser
import com.advault.vpn.core.dns.DnsRcode
import com.advault.vpn.core.dns.DnsType
import com.advault.vpn.core.packet.Checksum
import com.advault.vpn.core.packet.IpPacketBuilder
import com.advault.vpn.core.packet.IpProtocol
import com.advault.vpn.core.upstream.Upstream
import com.advault.vpn.core.upstream.UpstreamException
import com.advault.vpn.core.upstream.UpstreamResult
import com.advault.data.repository.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DnsQueryHandlerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = CapturingWriter()
    private val events = CopyOnWriteArrayList<QueryEvent>()
    private val upstreamCalls = AtomicInteger()
    private val ip = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

    private val echoUpstream = object : Upstream {
        override val description = "fake"
        override fun resolve(query: ByteArray, timeoutMs: Int): UpstreamResult {
            upstreamCalls.incrementAndGet()
            return UpstreamResult(TestDns.answer(query, listOf(ip), ttl = 300), "fake", 3)
        }
    }

    private val filter = FakeFilter(mapOf(
        "ads.example.com" to FilterCategory.ADVERTISING,
        "track.example.com" to FilterCategory.TRACKING,
    ))

    private fun handler(
        policy: com.advault.vpn.core.AppPolicy = MapPolicy(emptyMap()),
        uid: Int = 10123,
        upstream: Upstream? = echoUpstream,
        config: HandlerConfig = HandlerConfig(),
        maxPayload: Int = 16_000,
    ) = DnsQueryHandler(
        virtualDnsAddresses = listOf(TestDns.VDNS_V4, TestDns.VDNS_V6),
        filter = filter, policy = policy,
        classifier = ProviderClassifier { d -> if (d.endsWith("example.com")) "example-provider" else null },
        uidResolver = UidResolver { _, _, _, _, _ -> uid },
        recorder = QueryRecorder { events += it },
        writer = writer, cache = DnsCache(), upstream = { upstream }, ioScope = scope,
        maxUdpPayload = maxPayload,
    ).also { it.config = config }

    @After fun tearDown() { scope.cancel() }

    private fun send(h: DnsQueryHandler, dns: ByteArray, v6: Boolean = false) {
        val pkt = if (v6) TestDns.udpV6(dns) else TestDns.udpV4(dns)
        h.handlePacket(pkt, pkt.size)
    }

    /** Takes the reply, validates IP/UDP framing and checksums, returns the DNS payload. */
    private fun reply(v6: Boolean = false): ByteArray {
        val out = writer.take()
        TestDns.assertTransportChecksumValid(out)
        val p = TestDns.parse(out)
        if (!v6) assertTrue(Checksum.verifyIpv4Header(out))
        assertArrayEquals(if (v6) TestDns.VDNS_V6 else TestDns.VDNS_V4, p.srcAddress)
        assertArrayEquals(if (v6) TestDns.CLIENT_V6 else TestDns.CLIENT_V4, p.dstAddress)
        assertEquals(53, p.srcPort); assertEquals(40000, p.dstPort)
        return p.payload()
    }

    @Test fun blockedNullIpV4AndV6() {
        val h = handler()
        send(h, TestDns.query("ads.example.com", DnsType.A, id = 11))
        val r = reply()
        val info = DnsParser.parseResponse(r)!!
        assertEquals(11, info.id); assertEquals(DnsRcode.NOERROR, info.rcode); assertEquals(1, info.answerCount)
        assertArrayEquals(ByteArray(4), r.copyOfRange(r.size - 4, r.size))

        send(h, TestDns.query("ads.example.com", DnsType.AAAA, id = 12), v6 = true)
        val r6 = reply(v6 = true)
        assertArrayEquals(ByteArray(16), r6.copyOfRange(r6.size - 16, r6.size))
        assertEquals(0, upstreamCalls.get())
        val e = events.first()
        assertTrue(e.blocked); assertEquals(FilterCategory.ADVERTISING, e.category); assertEquals("pkg.10123", e.packageName)
        assertEquals("example-provider", e.providerId); assertEquals("test-list", e.listId)
        assertTrue(e.bytes > 0)
    }

    @Test fun blockedHttpsQueryIsNodata() {
        val h = handler()
        send(h, TestDns.query("ads.example.com", DnsType.HTTPS))
        val info = DnsParser.parseResponse(reply())!!
        assertEquals(DnsRcode.NOERROR, info.rcode); assertEquals(0, info.answerCount)
    }

    @Test fun blockResponseModes() {
        for ((mode, rc) in listOf(BlockResponse.NXDOMAIN to DnsRcode.NXDOMAIN, BlockResponse.REFUSED to DnsRcode.REFUSED)) {
            val h = handler(config = HandlerConfig(blockResponse = mode))
            for (t in listOf(DnsType.A, DnsType.AAAA, DnsType.HTTPS, DnsType.TXT)) {
                send(h, TestDns.query("ads.example.com", t))
                val info = DnsParser.parseResponse(reply())!!
                assertEquals("$mode/$t", rc, info.rcode); assertEquals(0, info.answerCount)
            }
        }
    }

    @Test fun allowedIsForwardedThenCached() {
        val h = handler()
        send(h, TestDns.query("www.example.org", id = 1))
        val first = DnsParser.parseResponse(reply())!!
        assertEquals(1, first.id); assertEquals(1, first.answerCount)
        send(h, TestDns.query("www.example.org", id = 2))
        val second = DnsParser.parseResponse(reply())!!
        assertEquals(2, second.id)
        assertEquals(1, upstreamCalls.get())
        Thread.sleep(50)
        assertTrue(events.any { it.cached })
        assertEquals(3, events.first { !it.cached }.latencyMs)
    }

    @Test fun cacheDisabledAlwaysForwards() {
        val h = handler(config = HandlerConfig(cacheEnabled = false))
        repeat(3) { send(h, TestDns.query("www.example.org", id = it)); reply() }
        assertEquals(3, upstreamCalls.get())
    }

    @Test fun excludedUidPassesThroughUnfiltered() {
        val h = handler(policy = MapPolicy(mapOf(10123 to null)))
        send(h, TestDns.query("ads.example.com", id = 5))
        val info = DnsParser.parseResponse(reply())!!
        assertEquals(1, info.answerCount)
        val r = writer.packets.size
        assertEquals(0, r)
        assertEquals(1, upstreamCalls.get())
        assertNull(filter.calls["ads.example.com"])
        Thread.sleep(50)
        assertEquals(DecisionReason.APP_EXCLUDED, events.single().reason)
        assertFalse(events.single().blocked)
    }

    @Test fun perAppModeResolution() {
        // Balanced app: tracking allowed; aggressive app: tracking blocked.
        val balanced = handler(policy = MapPolicy(mapOf(1 to FilterMode.BALANCED)), uid = 1)
        send(balanced, TestDns.query("track.example.com"))
        assertEquals(1, DnsParser.parseResponse(reply())!!.answerCount)
        assertEquals(FilterMode.BALANCED, filter.calls["track.example.com"])
        assertEquals(1, upstreamCalls.get())

        val aggressive = handler(policy = MapPolicy(mapOf(2 to FilterMode.AGGRESSIVE)), uid = 2)
        send(aggressive, TestDns.query("track.example.com"))
        val r = reply()
        assertArrayEquals(ByteArray(4), r.copyOfRange(r.size - 4, r.size))
        assertEquals(1, upstreamCalls.get())
    }

    @Test fun repositoryPolicyMapsUnknownUidToDefaultAndExcludedToNull() {
        val repo = object : AppRepository {
            override fun observeApps() = kotlinx.coroutines.flow.flowOf(emptyList<com.advault.data.models.InstalledApp>())
            override suspend fun setProtection(packageName: String, protection: com.advault.core.common.AppProtection) {}
            override suspend fun excludedPackages() = emptySet<String>()
            override fun modeForUid(uid: Int) = when (uid) { 100 -> null; 200 -> FilterMode.BALANCED; 300 -> error("boom"); else -> FilterMode.AGGRESSIVE }
            override fun packageForUid(uid: Int) = "p$uid"
            override suspend fun refresh() {}
        }
        var default = FilterMode.BALANCED
        val policy = RepositoryAppPolicy(repo) { default }
        assertEquals(FilterMode.BALANCED, policy.modeForUid(-1))
        default = FilterMode.AGGRESSIVE
        assertEquals(FilterMode.AGGRESSIVE, policy.modeForUid(-1))
        assertNull(policy.modeForUid(100))
        assertEquals(FilterMode.BALANCED, policy.modeForUid(200))
        assertEquals(FilterMode.AGGRESSIVE, policy.modeForUid(300))
        assertNull(policy.packageForUid(-1)); assertEquals("p5", policy.packageForUid(5))
    }

    @Test fun upstreamFailureGivesServfail() {
        val failing = object : Upstream {
            override val description = "down"
            override fun resolve(query: ByteArray, timeoutMs: Int): UpstreamResult = throw UpstreamException("down")
        }
        val h = handler(upstream = failing)
        send(h, TestDns.query("www.example.org", id = 3))
        val info = DnsParser.parseResponse(reply())!!
        assertEquals(DnsRcode.SERVFAIL, info.rcode); assertEquals(3, info.id)
        Thread.sleep(50)
        assertTrue(events.single().upstreamFailed)
        assertEquals(1L, h.counters.upstreamFailures.get())
    }

    @Test fun noUpstreamGivesServfail() {
        val h = handler(upstream = null)
        send(h, TestDns.query("www.example.org"))
        assertEquals(DnsRcode.SERVFAIL, DnsParser.parseResponse(reply())!!.rcode)
    }

    @Test fun malformedQueriesNeverThrowAndGetFormerrWhenPossible() {
        val h = handler()
        val bad = TestDns.query("example.com").copyOf(20)
        send(h, bad)
        val r = reply()
        assertEquals(DnsRcode.FORMERR, r[3].toInt() and 0xF)
        // too short for a header: dropped silently
        send(h, ByteArray(5))
        assertNull(writer.packets.poll())
        // random garbage packets
        val rnd = java.util.Random(1)
        repeat(3000) {
            val b = ByteArray(rnd.nextInt(200) + 1).also(rnd::nextBytes)
            h.handlePacket(b, b.size)
            val p = TestDns.udpV4(ByteArray(rnd.nextInt(60)).also(rnd::nextBytes))
            h.handlePacket(p, p.size)
        }
        writer.packets.clear()
        // handler still works afterwards
        send(h, TestDns.query("ads.example.com")); reply()
    }

    @Test fun nonDnsTrafficDroppedAndTcpReset() {
        val h = handler()
        val other = IpPacketBuilder.udp(TestDns.CLIENT_V4, 1, byteArrayOf(8, 8, 8, 8), 53, TestDns.query("a.com"))
        h.handlePacket(other, other.size)
        val wrongPort = IpPacketBuilder.udp(TestDns.CLIENT_V4, 1, TestDns.VDNS_V4, 123, ByteArray(48))
        h.handlePacket(wrongPort, wrongPort.size)
        assertNull(writer.packets.poll())
        assertEquals(2L, h.counters.droppedNonDns.get())

        val syn = ByteArray(40)
        syn[0] = 0x45; syn[3] = 40; syn[8] = 64; syn[9] = 6
        System.arraycopy(TestDns.CLIENT_V4, 0, syn, 12, 4); System.arraycopy(TestDns.VDNS_V4, 0, syn, 16, 4)
        syn[22] = 0; syn[23] = 53; syn[32] = 0x50; syn[33] = 0x02
        h.handlePacket(syn, syn.size)
        val rst = TestDns.parse(writer.take())
        assertEquals(IpProtocol.TCP, rst.protocol)
        assertEquals(1L, h.counters.tcpResets.get())
    }

    @Test fun oversizedAnswerIsTruncated() {
        val big = object : Upstream {
            override val description = "big"
            override fun resolve(query: ByteArray, timeoutMs: Int) =
                UpstreamResult(TestDns.answer(query, List(100) { byteArrayOf(10, 0, 0, it.toByte()) }), "big", 1)
        }
        val h = handler(upstream = big, maxPayload = 512)
        send(h, TestDns.query("www.example.org"))
        val r = reply()
        assertTrue(r[2].toInt() and 0x02 != 0)
        assertTrue(r.size <= 512)
    }

    @Test fun filterExceptionFailsOpen() {
        val broken = object : com.advault.data.repository.FilterEngine by filter {
            override fun evaluate(domain: String, mode: FilterMode) = error("engine bug")
        }
        val h = DnsQueryHandler(listOf(TestDns.VDNS_V4), broken, MapPolicy(emptyMap()), ProviderClassifier.NONE,
            UidResolver.UNKNOWN, QueryRecorder.NONE, writer, DnsCache(), { echoUpstream }, scope)
        send(h, TestDns.query("ads.example.com"))
        assertEquals(1, DnsParser.parseResponse(reply())!!.answerCount)
        assertEquals(1L, h.counters.filterErrors.get())
    }

    @Test fun singleLabelAndReverseNamesSkipFilterButForward() {
        val h = handler()
        send(h, TestDns.query("localhost")); reply()
        assertNull(filter.calls["localhost"])
        assertEquals(1, upstreamCalls.get())
    }
}
