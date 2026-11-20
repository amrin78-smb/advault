package com.advault.intelligence

import com.advault.core.common.AdFormat
import com.advault.core.common.FilterCategory
import com.advault.data.models.ProviderDomain
import com.advault.intelligence.provider.ProviderMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMatcherTest {
    private fun dom(p: String) = ProviderDomain(p, FilterCategory.ADVERTISING, listOf(AdFormat.UNKNOWN))
    private val matcher = ProviderMatcher.build(
        listOf(
            TestData.provider("generic", listOf(dom("example.com"), dom("*.wild.net"))),
            TestData.provider("specific", listOf(dom("ads.g.example.com"))),
        ),
    )

    @Test fun suffixSemantics() {
        assertEquals("generic", matcher.match("example.com")?.provider?.id)
        assertEquals("generic", matcher.match("ads.example.com")?.provider?.id)
        assertEquals("generic", matcher.match("a.b.c.example.com")?.provider?.id)
        assertEquals("specific", matcher.match("ads.g.example.com")?.provider?.id)
        assertEquals("specific", matcher.match("x.ads.g.example.com")?.provider?.id)
        assertNull(matcher.match("notexample.com"))
        assertNull(matcher.match("example.com.evil.org"))
        assertNull(matcher.match("wild.net"))
        assertEquals("generic", matcher.match("x.wild.net")?.provider?.id)
        assertNull(matcher.match(""))
        assertNull(ProviderMatcher.EMPTY.match("example.com"))
    }

    @Test fun bundledMatches() {
        val m = ProviderMatcher.build(TestData.bundled().providers)
        assertEquals("google_admob", m.match("googleads.g.doubleclick.net")?.provider?.id)
        assertEquals("google_ad_manager", m.match("ad.doubleclick.net")?.provider?.id)
        assertEquals("unity_ads", m.match("auction.unityads.unity3d.com")?.provider?.id)
        assertNull(m.match("api.uca.cloud.unity3d.com"))
        assertNull(m.match("www.googleapis.com"))
        assertEquals(false, m.match("graph.facebook.com")?.domain?.blockable)
    }

    @Test fun millionLookupsAreFast() {
        val m = ProviderMatcher.build(TestData.bundled().providers)
        val domains = arrayOf("googleads.g.doubleclick.net", "auction.unityads.unity3d.com", "www.example.org",
            "a.b.c.d.e.f.notanad.com", "d.applovin.com", "clients4.google.com", "api.github.com", "x.app-measurement.com")
        repeat(50_000) { m.match(domains[it % domains.size]) } // warm-up
        val start = System.nanoTime()
        var hits = 0
        for (i in 0 until 1_000_000) if (m.match(domains[i % domains.size]) != null) hits++
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(500_000, hits)
        assertTrue("1M lookups took ${ms}ms", ms < 3_000)
    }
}
