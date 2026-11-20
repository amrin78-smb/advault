package com.advault.vpn

import com.advault.vpn.core.dns.DnsCache
import com.advault.vpn.core.dns.DnsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCacheTest {
    private var now = 1_000_000L
    private val ip = byteArrayOf(1, 2, 3, 4)

    private fun q(name: String, id: Int = 1, type: Int = 1) = DnsParser.parseQuery(TestDns.query(name, type, id))!!

    @Test fun hitRewritesIdAndDecrementsTtl() {
        val cache = DnsCache(clock = { now })
        val query = q("example.com", id = 100)
        assertTrue(cache.put(query, TestDns.answer(query.raw, listOf(ip), ttl = 300)))
        now += 100_000 // 100 s later
        val hit = cache.get(q("example.com", id = 555))!!
        val info = DnsParser.parseResponse(hit)!!
        assertEquals(555, info.id)
        assertEquals(200L, DnsParser.u32(hit, info.ttlOffsets[0]))
        assertEquals(1.0 / 1.0, cache.hitRate, 0.0)
    }

    @Test fun keyIsCaseInsensitiveAndEchoesClientCase() {
        val cache = DnsCache(clock = { now })
        val query = q("example.com")
        cache.put(query, TestDns.answer(query.raw, listOf(ip)))
        val hit = cache.get(q("ExAmPlE.CoM", id = 2))!!
        assertEquals("ExAmPlE.CoM", DnsParser.parseResponse(hit)!!.questions[0].name)
    }

    @Test fun differentTypeIsMiss() {
        val cache = DnsCache(clock = { now })
        val query = q("example.com")
        cache.put(query, TestDns.answer(query.raw, listOf(ip)))
        assertNull(cache.get(q("example.com", type = 28)))
    }

    @Test fun expiresAfterTtlWithMinClamp() {
        val cache = DnsCache(minTtlSeconds = 10, maxTtlSeconds = 3600, clock = { now })
        val query = q("short.example")
        cache.put(query, TestDns.answer(query.raw, listOf(ip), ttl = 2))
        now += 5_000
        assertNotNull("min clamp keeps it 10 s", cache.get(q("short.example")))
        now += 6_000
        assertNull(cache.get(q("short.example")))
        assertEquals(0, cache.size)
    }

    @Test fun maxClampCapsLifetime() {
        val cache = DnsCache(minTtlSeconds = 10, maxTtlSeconds = 60, clock = { now })
        val query = q("long.example")
        cache.put(query, TestDns.answer(query.raw, listOf(ip), ttl = 86_400))
        now += 59_000
        val hit = cache.get(q("long.example"))!!
        assertTrue(DnsParser.u32(hit, DnsParser.parseResponse(hit)!!.ttlOffsets[0]) <= 60)
        now += 2_000
        assertNull(cache.get(q("long.example")))
    }

    @Test fun lruEvictsLeastRecentlyUsed() {
        val cache = DnsCache(maxEntries = 3, clock = { now })
        for (n in listOf("a.com", "b.com", "c.com")) { val x = q(n); cache.put(x, TestDns.answer(x.raw, listOf(ip))) }
        cache.get(q("a.com")) // a becomes most recent
        val d = q("d.com"); cache.put(d, TestDns.answer(d.raw, listOf(ip)))
        assertEquals(3, cache.size)
        assertNull(cache.get(q("b.com")))
        assertNotNull(cache.get(q("a.com")))
        assertNotNull(cache.get(q("d.com")))
    }

    @Test fun doesNotCacheServfailTruncatedOrMultiQuestion() {
        val cache = DnsCache(clock = { now })
        val query = q("x.com")
        assertFalse(cache.put(query, TestDns.answer(query.raw, emptyList(), rcode = 2)))
        assertFalse(cache.put(query, TestDns.answer(query.raw, listOf(ip), tc = true)))
        assertFalse(cache.put(query, byteArrayOf(1, 2, 3)))
        val multi = DnsParser.parseQuery(TestDns.query("a.com" to 1, "b.com" to 1))!!
        assertFalse(cache.put(multi, TestDns.answer(query.raw, listOf(ip))))
        assertTrue(cache.put(query, TestDns.answer(query.raw, emptyList(), rcode = 3))) // NXDOMAIN cached
    }
}
