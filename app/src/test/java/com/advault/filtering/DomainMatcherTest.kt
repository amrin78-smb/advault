package com.advault.filtering

import com.advault.filtering.matcher.DomainHash
import com.advault.filtering.matcher.DomainMatcher
import com.advault.filtering.matcher.DomainTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    private fun matcher(vararg rules: Pair<String, Long>): DomainMatcher {
        val b = DomainMatcher.Builder()
        rules.forEach { (r, bits) -> b.addRule(r, bits) }
        return b.build()
    }

    @Test fun listEntryMatchesDomainAndSubdomains() {
        val m = matcher("example.com" to 1L)
        assertEquals(1L, m.unionMask("example.com"))
        assertEquals(1L, m.unionMask("ads.example.com"))
        assertEquals(1L, m.unionMask("a.b.c.example.com"))
        assertEquals(0L, m.unionMask("notexample.com"))
        assertEquals(0L, m.unionMask("example.com.evil.net"))
        assertEquals(0L, m.unionMask("example.org"))
        assertEquals(0L, m.unionMask("com"))
    }

    @Test fun wildcardMatchesSubdomainsOnly() {
        val m = matcher("*.example.com" to 2L)
        assertEquals(0L, m.unionMask("example.com"))
        assertEquals(2L, m.unionMask("x.example.com"))
        assertEquals(2L, m.unionMask("y.x.example.com"))
        assertEquals(0L, m.unionMask("xexample.com"))
    }

    @Test fun exactMatchesOnlyTheName() {
        val m = matcher("=example.com" to 4L)
        assertEquals(4L, m.unionMask("example.com"))
        assertEquals(0L, m.unionMask("www.example.com"))
    }

    @Test fun bitsAreUnionedAcrossLevelsAndLists() {
        val m = matcher("example.com" to 1L, "ads.example.com" to 2L, "example.com" to 8L)
        assertEquals(9L, m.unionMask("example.com"))
        assertEquals(11L, m.unionMask("x.ads.example.com"))
    }

    @Test fun firstMatchReportsMostSpecificRule() {
        val m = matcher("example.com" to 1L, "*.ads.example.com" to 2L)
        val d = "x.ads.example.com"
        assertEquals("*.ads.example.com", DomainMatcher.ruleText(d, m.firstMatch(d)))
        assertEquals("example.com", DomainMatcher.ruleText(d, m.firstMatchFor(d, 1L)))
        assertEquals(-1, m.firstMatch("other.org"))
    }

    @Test fun caseAndTrailingDotAreTolerated() {
        val m = matcher("example.com" to 1L)
        assertEquals(1L, m.unionMask("ADS.Example.COM"))
        assertEquals(1L, m.unionMask("ads.example.com."))
        assertEquals("example.com", DomainMatcher.ruleText("ADS.EXAMPLE.COM.", m.firstMatch("ADS.EXAMPLE.COM.")))
    }

    @Test fun degenerateInputsDoNotCrash() {
        val m = matcher("example.com" to 1L)
        for (d in listOf("", ".", "..", "com", ".example.com", "a..example.com", "example.com..")) m.unionMask(d)
        assertEquals(1L, m.unionMask(".example.com"))
        assertTrue(DomainMatcher.EMPTY.isEmpty)
        assertEquals(0L, DomainMatcher.EMPTY.unionMask("example.com"))
    }

    @Test fun hashOfWholeEqualsSuffixHash() {
        // The stored hash of "example.com" must equal the incremental suffix hash inside a longer name.
        var h = DomainHash.SEED
        val q = "a.example.com"
        for (i in q.length - 1 downTo 2) h = DomainHash.step(h, q[i])
        assertEquals(DomainHash.of("example.com"), DomainHash.finish(h))
    }

    @Test fun tableGrowsAndKeepsAllEntries() {
        val b = DomainTable.Builder(1)
        for (i in 1..50_000) b.put(DomainHash.of("d$i.test"), (i % 7 + 1).toLong())
        val t = b.build()
        assertEquals(50_000, t.size)
        for (i in 1..50_000) assertEquals((i % 7 + 1).toLong(), t.get(DomainHash.of("d$i.test")))
        assertEquals(0L, t.get(DomainHash.of("missing.test")))
        assertFalse(t.capacity * DomainTable.LOAD_FACTOR < t.size)
    }
}
