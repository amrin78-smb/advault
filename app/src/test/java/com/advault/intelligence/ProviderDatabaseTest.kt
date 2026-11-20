package com.advault.intelligence

import com.advault.core.common.DomainUtils
import com.advault.core.common.FilterCategory
import com.advault.intelligence.provider.ProviderDatabaseParser
import com.advault.intelligence.provider.ProviderValidationException
import com.advault.intelligence.provider.blockRules
import com.advault.intelligence.provider.blockableDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderDatabaseTest {

    private fun assertRejected(text: String) {
        try {
            ProviderDatabaseParser.parse(text, "test")
            fail("Expected rejection")
        } catch (_: ProviderValidationException) {
        }
    }

    @Test fun bundledDatabaseParsesAndValidates() {
        val db = TestData.bundled()
        assertEquals(ProviderDatabaseParser.SUPPORTED_SCHEMA_VERSION, db.schemaVersion)
        assertTrue(db.providers.size >= 25)
        assertTrue(db.domainCount >= 50)
        val required = listOf(
            "google_admob", "google_ad_manager", "unity_ads", "applovin", "ironsource", "meta_audience_network",
            "liftoff_vungle", "chartboost", "mintegral", "pangle", "inmobi", "digital_turbine", "moloco", "amazon_aps",
            "yandex_ads", "appodeal", "startio", "tapjoy", "appsflyer", "adjust", "branch", "kochava", "singular",
            "google_analytics_firebase", "gameanalytics", "flurry", "firebase_crashlytics",
        )
        val ids = db.providers.map { it.id }.toSet()
        required.forEach { assertTrue("missing $it", it in ids) }
    }

    @Test fun everyBundledDomainIsValidAndUnique() {
        val db = TestData.bundled()
        val patterns = db.providers.flatMap { p -> p.domains.map { it.pattern } }
        patterns.forEach { assertTrue(it, DomainUtils.isValidDomain(it.removePrefix("*."))) }
        assertEquals(patterns.size, patterns.toSet().size)
        assertEquals(db.providers.size, db.providers.map { it.id }.toSet().size)
        db.providers.forEach { assertTrue(it.id, it.sdkSignatures.isNotEmpty() || it.sdkMetaDataKeys.isNotEmpty()) }
    }

    @Test fun sharedInfrastructureIsNotBlockable() {
        val db = TestData.bundled()
        val all = db.providers.flatMap { it.domains }
        for (shared in listOf("graph.facebook.com", "www.googleapis.com", "googleapis.com", "gstatic.com", "unity3d.com", "facebook.com", "app.link")) {
            all.filter { it.pattern == shared }.forEach { assertFalse("$shared must not be blockable", it.blockable) }
        }
        assertNotNull(all.firstOrNull { it.pattern == "graph.facebook.com" && !it.blockable })
        // No blockable rule may cover a protected host.
        db.providers.blockRules().forEach { assertFalse(it.domain, ProviderDatabaseParser.coversProtectedHost(it.domain)) }
        val rules = db.providers.blockRules().map { it.domain }.toSet()
        assertFalse("graph.facebook.com" in rules)
        assertTrue("googleads.g.doubleclick.net" in rules)
        val meta = db.providers.first { it.id == "meta_audience_network" }
        assertTrue(meta.blockableDomains().none { it.pattern == "graph.facebook.com" })
    }

    @Test fun validSyntheticDatabaseParses() {
        val db = ProviderDatabaseParser.parse(TestData.dbJson(), "test")
        val p = db.providers.single()
        assertEquals(listOf("com.example.ads."), p.sdkSignatures)
        assertEquals(listOf("example.key"), p.sdkMetaDataKeys)
        assertEquals(FilterCategory.ADVERTISING, p.domains.single().category)
        assertTrue(p.domains.single().blockable)
    }

    @Test fun rejectsInvalidVariants() {
        assertRejected(TestData.dbJson(schemaVersion = 2))
        assertRejected(TestData.dbJson(dataVersion = "latest"))
        assertRejected(TestData.dbJson(id = "Bad Id!"))
        assertRejected(TestData.dbJson(domainsJson = """[{"pattern":"not a domain","category":"ADVERTISING"}]"""))
        assertRejected(TestData.dbJson(domainsJson = """[{"pattern":"localhost","category":"ADVERTISING"}]"""))
        assertRejected(TestData.dbJson(domainsJson = """[{"pattern":"1.2.3.4","category":"ADVERTISING"}]"""))
        assertRejected(TestData.dbJson(domainsJson = """[{"pattern":"a*.example.com","category":"ADVERTISING"}]"""))
        assertRejected(TestData.dbJson(domainsJson = """[{"pattern":"ads.example.com","category":"SPYWARE"}]"""))
        assertRejected(TestData.dbJson(domainsJson = """[{"pattern":"ads.example.com","category":"ADVERTISING","formats":["POPUP"]}]"""))
        assertRejected(TestData.dbJson(domainsJson = """[{"pattern":"a.example.com","category":"ADVERTISING"},{"pattern":"a.example.com","category":"ADVERTISING"}]"""))
        assertRejected(TestData.dbJson(domainsJson = "[]"))
        // Blockable shared infrastructure (or a parent of it) is rejected; non-blockable is accepted.
        assertRejected(TestData.dbJson(domainsJson = """[{"pattern":"facebook.com","category":"ADVERTISING"}]"""))
        assertRejected(TestData.dbJson(domainsJson = """[{"pattern":"graph.facebook.com","category":"ADVERTISING"}]"""))
        ProviderDatabaseParser.parse(TestData.dbJson(domainsJson = """[{"pattern":"graph.facebook.com","category":"ADVERTISING","blockable":false}]"""), "t")
        assertRejected("{not json")
        assertRejected("""{"schemaVersion":1}""")
    }

    @Test fun rejectsOversizedAndDuplicateIds() {
        val big = " ".repeat(ProviderDatabaseParser.MAX_BYTES + 1)
        assertRejected(TestData.dbJson() + big)
        val domains = (0..ProviderDatabaseParser.MAX_DOMAINS_PER_PROVIDER).joinToString(",", "[", "]") {
            """{"pattern":"a$it.example.com","category":"ADVERTISING"}"""
        }
        assertRejected(TestData.dbJson(domainsJson = domains))
        val p = """{"id":"x1","name":"X","description":"","lastUpdated":"2026-11-20","domains":[{"pattern":"PAT","category":"ADVERTISING"}]}"""
        assertRejected("""{"schemaVersion":1,"dataVersion":"2026.1.1.1","updatedAt":"2026-11-20","providers":[${p.replace("PAT", "a.com")},${p.replace("PAT", "b.com")}]}""")
    }

    @Test fun dataVersionComparison() {
        assertTrue(ProviderDatabaseParser.compareDataVersions("2026.11.20.2", "2026.11.20.1") > 0)
        assertTrue(ProviderDatabaseParser.compareDataVersions("2026.11.20.10", "2026.11.20.9") > 0)
        assertTrue(ProviderDatabaseParser.compareDataVersions("2027.1.1.1", "2026.12.31.99") > 0)
        assertEquals(0, ProviderDatabaseParser.compareDataVersions("2026.11.20.1", "2026.11.20.1"))
        assertTrue(ProviderDatabaseParser.compareDataVersions("garbage", "2026.11.20.1") < 0)
    }

    @Test fun overrideUsedOnlyWhenNewer() {
        val bundled = ProviderDatabaseParser.parse(TestData.dbJson(dataVersion = "2026.11.20.1"), "bundled")
        val newer = ProviderDatabaseParser.parse(TestData.dbJson(dataVersion = "2026.11.21.1"), "download")
        val older = ProviderDatabaseParser.parse(TestData.dbJson(dataVersion = "2026.11.19.5"), "download")
        val same = ProviderDatabaseParser.parse(TestData.dbJson(dataVersion = "2026.11.20.1"), "download")
        assertSame(newer, ProviderDatabaseParser.choose(bundled, newer))
        assertSame(bundled, ProviderDatabaseParser.choose(bundled, older))
        assertSame(bundled, ProviderDatabaseParser.choose(bundled, same))
        assertSame(bundled, ProviderDatabaseParser.choose(bundled, null))
    }

    @Test fun bundledGamesDatabaseParses() {
        val games = com.advault.intelligence.reward.GameCompatibilityDatabase.parse(TestData.bundledGamesText())
        assertTrue(games.entries.isEmpty()) // Ships without unverified claims about real games.
    }
}
