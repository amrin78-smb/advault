package com.advault.filtering

import com.advault.core.common.AdFormat
import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.core.common.RuleType
import com.advault.core.database.CustomRuleEntity
import com.advault.data.models.AdProvider
import com.advault.data.models.ProviderDomain
import com.advault.filtering.engine.FilterSnapshot
import com.advault.filtering.engine.FilterSnapshotBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterSnapshotTest {

    private val all = FilterSnapshot.categoryMask(FilterCategory.entries)

    private fun build(
        lists: Map<Pair<String, FilterCategory>, List<String>> = emptyMap(),
        custom: List<CustomRuleEntity> = emptyList(),
        allow: String = "",
        providers: List<AdProvider> = emptyList(),
    ): FilterSnapshot {
        val b = FilterSnapshotBuilder()
        for ((k, rules) in lists) {
            val bits = b.source(k.first, k.second)
            rules.forEach { b.addListRule(it, bits) }
        }
        b.addCustomRules(custom)
        b.addBuiltinAllow(allow)
        b.addProviders(providers, "provider-intelligence")
        return b.build()
    }

    private fun rule(d: String, t: RuleType) = CustomRuleEntity(domain = d, type = t, createdAt = 0)

    private val snap = build(
        lists = mapOf(
            ("ads" to FilterCategory.ADVERTISING) to listOf("ads.example.com", "doubleclick.net"),
            ("trk" to FilterCategory.TRACKING) to listOf("tracker.example.com", "doubleclick.net"),
            ("ana" to FilterCategory.ANALYTICS) to listOf("analytics.example.org"),
            ("mal" to FilterCategory.MALICIOUS) to listOf("evil.test"),
        ),
        custom = listOf(
            rule("allowed.ads.example.com", RuleType.ALLOW),
            rule("*.evil.test", RuleType.ALLOW),
            rule("custom-block.org", RuleType.BLOCK),
            rule("ads.example.com", RuleType.BLOCK),
            rule("both.net", RuleType.BLOCK),
            rule("both.net", RuleType.ALLOW),
        ),
    )

    @Test fun noMatch() {
        val v = snap.evaluate("www.wikipedia.org", FilterMode.AGGRESSIVE, all)
        assertFalse(v.blocked); assertEquals(DecisionReason.NO_MATCH, v.reason); assertNull(v.category)
    }

    @Test fun customAllowBeatsCustomBlockAndLists() {
        val v = snap.evaluate("allowed.ads.example.com", FilterMode.AGGRESSIVE, all)
        assertFalse(v.blocked); assertEquals(DecisionReason.CUSTOM_ALLOW, v.reason)
        assertEquals("allowed.ads.example.com", v.matchedRule)
        val both = snap.evaluate("x.both.net", FilterMode.AGGRESSIVE, all)
        assertFalse(both.blocked); assertEquals(DecisionReason.CUSTOM_ALLOW, both.reason)
    }

    @Test fun wildcardAllowOnlyCoversSubdomains() {
        val sub = snap.evaluate("cdn.evil.test", FilterMode.AGGRESSIVE, all)
        assertEquals(DecisionReason.CUSTOM_ALLOW, sub.reason); assertEquals("*.evil.test", sub.matchedRule)
        val apex = snap.evaluate("evil.test", FilterMode.AGGRESSIVE, all)
        assertTrue(apex.blocked); assertEquals(FilterCategory.MALICIOUS, apex.category); assertEquals("mal", apex.listId)
    }

    @Test fun customBlockBeatsLists() {
        val v = snap.evaluate("a.custom-block.org", FilterMode.BALANCED, 0)
        assertTrue(v.blocked); assertEquals(DecisionReason.CUSTOM_BLOCK, v.reason); assertEquals("custom-block.org", v.matchedRule)
    }

    @Test fun listMatchBlocksWithCategoryRuleAndListId() {
        val v = snap.evaluate("x.tracker.example.com", FilterMode.AGGRESSIVE, all)
        assertTrue(v.blocked); assertEquals(DecisionReason.BLOCKLIST, v.reason)
        assertEquals(FilterCategory.TRACKING, v.category); assertEquals("tracker.example.com", v.matchedRule); assertEquals("trk", v.listId)
    }

    @Test fun balancedModeExcludesTrackingAndAnalytics() {
        val t = snap.evaluate("tracker.example.com", FilterMode.BALANCED, all)
        assertFalse(t.blocked); assertEquals(DecisionReason.MODE_EXCLUDES_CATEGORY, t.reason); assertEquals(FilterCategory.TRACKING, t.category)
        val a = snap.evaluate("analytics.example.org", FilterMode.BALANCED, all)
        assertEquals(DecisionReason.MODE_EXCLUDES_CATEGORY, a.reason)
        assertTrue(snap.evaluate("evil.test", FilterMode.BALANCED, all).blocked)
    }

    @Test fun domainInSeveralCategoriesBlocksIfAnyIsActive() {
        // doubleclick.net is ADVERTISING + TRACKING.
        val balanced = snap.evaluate("stats.doubleclick.net", FilterMode.BALANCED, all)
        assertTrue(balanced.blocked); assertEquals(FilterCategory.ADVERTISING, balanced.category); assertEquals("ads", balanced.listId)
        val adsOff = all and (1 shl FilterCategory.ADVERTISING.ordinal).inv()
        val aggressive = snap.evaluate("stats.doubleclick.net", FilterMode.AGGRESSIVE, adsOff)
        assertTrue(aggressive.blocked); assertEquals(FilterCategory.TRACKING, aggressive.category); assertEquals("trk", aggressive.listId)
        val bothOff = adsOff and (1 shl FilterCategory.TRACKING.ordinal).inv()
        val off = snap.evaluate("stats.doubleclick.net", FilterMode.AGGRESSIVE, bothOff)
        assertFalse(off.blocked); assertEquals(DecisionReason.CATEGORY_DISABLED, off.reason)
    }

    @Test fun disabledCategoryVsModeExclusion() {
        val noTracking = all and (1 shl FilterCategory.TRACKING.ordinal).inv()
        val v = snap.evaluate("tracker.example.com", FilterMode.AGGRESSIVE, noTracking)
        assertFalse(v.blocked); assertEquals(DecisionReason.CATEGORY_DISABLED, v.reason); assertEquals(FilterCategory.TRACKING, v.category)
        val v2 = snap.evaluate("tracker.example.com", FilterMode.BALANCED, noTracking)
        assertEquals(DecisionReason.CATEGORY_DISABLED, v2.reason)
    }

    @Test fun builtinAllowOverridesListsButNotCustomBlock() {
        val s = build(
            lists = mapOf(("ads" to FilterCategory.ADVERTISING) to listOf("google.com", "graph.facebook.com")),
            custom = listOf(rule("www.google.com", RuleType.BLOCK)),
            allow = "# comment\n=google.com\nwww.google.com\ngraph.facebook.com  # shared API\n",
        )
        val apex = s.evaluate("google.com", FilterMode.AGGRESSIVE, all)
        assertFalse(apex.blocked); assertEquals(FilterSnapshot.BUILTIN_ALLOW_ID, apex.listId)
        assertTrue(s.evaluate("adservice.google.com", FilterMode.AGGRESSIVE, all).blocked) // exact allow does not cover subdomains
        assertFalse(s.evaluate("graph.facebook.com", FilterMode.AGGRESSIVE, all).blocked)
        assertEquals(DecisionReason.CUSTOM_BLOCK, s.evaluate("www.google.com", FilterMode.AGGRESSIVE, all).reason)
    }

    @Test fun providerDomainsAreAPerCategorySource() {
        val p = AdProvider(
            id = "unity", name = "Unity Ads", description = "", lastUpdated = "",
            domains = listOf(
                ProviderDomain("unityads.unity3d.com", FilterCategory.GAME_ADS, listOf(AdFormat.REWARDED)),
                ProviderDomain("*.cdp.example.com", FilterCategory.ANALYTICS, listOf(AdFormat.TRACKING)),
                ProviderDomain("not a domain", FilterCategory.GAME_ADS, emptyList()),
            ),
            sdkSignatures = emptyList(), supportsServerSideVerification = true,
        )
        val s = build(providers = listOf(p))
        val v = s.evaluate("auction.unityads.unity3d.com", FilterMode.BALANCED, all)
        assertTrue(v.blocked); assertEquals(FilterCategory.GAME_ADS, v.category); assertEquals("provider-intelligence", v.listId)
        assertFalse(s.evaluate("cdp.example.com", FilterMode.AGGRESSIVE, all).blocked)
        assertTrue(s.evaluate("x.cdp.example.com", FilterMode.AGGRESSIVE, all).blocked)
        assertFalse(s.evaluate("unity3d.com", FilterMode.AGGRESSIVE, all).blocked)
        assertEquals(2, s.totalDomains)
        assertEquals(mapOf(FilterCategory.GAME_ADS to 1, FilterCategory.ANALYTICS to 1), s.domainsPerCategory)
    }

    @Test fun analyticsClassificationOverridesGenericTracking() {
        val s = build(
            lists = mapOf(
                ("trk" to FilterCategory.TRACKING) to listOf("app-measurement.com", "tracker.test"),
                ("ana" to FilterCategory.ANALYTICS) to listOf("app-measurement.com"),
                ("mal" to FilterCategory.MALICIOUS) to listOf("app-measurement.com"),
            ),
        )
        val noAnalytics = all and (1 shl FilterCategory.ANALYTICS.ordinal).inv()
        val noAnaNoMal = noAnalytics and (1 shl FilterCategory.MALICIOUS.ordinal).inv()
        val v = s.evaluate("app-measurement.com", FilterMode.AGGRESSIVE, noAnaNoMal)
        assertFalse(v.blocked); assertEquals(DecisionReason.CATEGORY_DISABLED, v.reason); assertEquals(FilterCategory.ANALYTICS, v.category)
        assertTrue(s.evaluate("app-measurement.com", FilterMode.AGGRESSIVE, noAnalytics).blocked) // malicious still applies
        assertTrue(s.evaluate("tracker.test", FilterMode.AGGRESSIVE, noAnaNoMal).blocked)
        assertEquals(FilterCategory.MALICIOUS, s.evaluate("app-measurement.com", FilterMode.AGGRESSIVE, all).category)
    }

    @Test fun moreThan64SourcesStillMatch() {
        val b = FilterSnapshotBuilder()
        for (i in 0 until 80) b.addListRule("d$i.example", b.source("list$i", FilterCategory.entries[i % 6]))
        val s = b.build()
        for (i in 0 until 80) assertTrue(s.evaluate("x.d$i.example", FilterMode.AGGRESSIVE, all).blocked)
    }

    @Test fun emptySnapshotFailsOpen() {
        assertEquals(DecisionReason.NO_MATCH, FilterSnapshot.EMPTY.evaluate("ads.example.com", FilterMode.AGGRESSIVE, all).reason)
    }
}
