package com.advault.filtering

import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.filtering.data.BuiltInLists
import com.advault.filtering.engine.FilterSnapshot
import com.advault.filtering.engine.FilterSnapshotBuilder
import com.advault.filtering.parser.BlocklistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Regression tests against the ACTUAL bundled assets, parsed with the production parser.
 * Any new or refreshed list that breaks essential services must be fixed with an entry in
 * assets/blocklists/builtin_allow.txt (or by not shipping the list).
 */
class BundledListsTest {

    companion object {
        val assetDir = File("src/main/assets/blocklists")
        lateinit var snapshot: FilterSnapshot
        val counts = LinkedHashMap<String, Int>()
        private val allCategories = FilterSnapshot.categoryMask(FilterCategory.entries)

        fun buildFromAssets(): FilterSnapshot {
            val b = FilterSnapshotBuilder(300_000)
            for (def in BuiltInLists.all) {
                val bits = b.source(def.id, def.category)
                val stats = File(assetDir, def.asset).inputStream().use { input ->
                    BlocklistParser.parse(input) { d, sub -> b.addListRule(if (sub) "*.$d" else d, bits) }
                }
                counts[def.id] = stats.validRules
            }
            b.addBuiltinAllow(File(assetDir, BuiltInLists.BUILTIN_ALLOW_ASSET).readText())
            return b.build()
        }

        @BeforeClass @JvmStatic fun load() {
            val t0 = System.nanoTime()
            snapshot = buildFromAssets()
            println("Bundled lists parsed+built in ${(System.nanoTime() - t0) / 1_000_000} ms: $counts")
            println("Unique domains: ${snapshot.totalDomains}, per category: ${snapshot.domainsPerCategory}, matcher arrays: ${snapshot.lists.memoryBytes / 1024} KiB")
        }

        /** Essential domains that must never be blocked in AGGRESSIVE mode. */
        val MUST_NOT_BLOCK = listOf(
            // Android connectivity / Play / push
            "connectivitycheck.gstatic.com", "connectivitycheck.android.com", "www.google.com", "google.com",
            "android.clients.google.com", "android.googleapis.com", "play.googleapis.com", "play.google.com",
            "play-fe.googleapis.com", "play-lh.googleusercontent.com", "accounts.google.com", "oauth2.googleapis.com",
            "www.googleapis.com", "firebaseinstallations.googleapis.com", "fcm.googleapis.com", "mtalk.google.com",
            "clients4.google.com", "clientservices.googleapis.com", "fonts.gstatic.com", "fonts.googleapis.com",
            "www.gstatic.com", "drive.google.com", "mail.google.com", "gmail.com", "maps.googleapis.com",
            "firebasestorage.googleapis.com", "firestore.googleapis.com",
            // YouTube
            "www.youtube.com", "youtube.com", "m.youtube.com", "youtubei.googleapis.com",
            "rr3---sn-4g5e6nsz.googlevideo.com", "r1---sn-abcdef.googlevideo.com", "i.ytimg.com", "yt3.ggpht.com",
            // Social / messaging
            "www.facebook.com", "facebook.com", "graph.facebook.com", "static.xx.fbcdn.net", "scontent.xx.fbcdn.net",
            "api.twitter.com", "twitter.com", "x.com", "abs.twimg.com", "pbs.twimg.com",
            "www.instagram.com", "instagram.com", "i.instagram.com",
            "whatsapp.net", "g.whatsapp.net", "mmg.whatsapp.net", "whatsapp.com", "web.whatsapp.com",
            "discord.com", "gateway.discord.gg", "cdn.discordapp.com", "telegram.org", "web.telegram.org", "signal.org",
            "www.reddit.com", "reddit.com",
            // Media
            "netflix.com", "www.netflix.com", "api-global.netflix.com", "nflxvideo.net", "occ-0-1-2.1.nflxso.net",
            "spotify.com", "open.spotify.com", "api.spotify.com", "spclient.wg.spotify.com",
            // Games / CDNs
            "unity3d.com", "cdn.unity3d.com", "store.steampowered.com", "steamcommunity.com", "steamcdn-a.akamaihd.net",
            "epicgames.com", "www.epicgames.com",
            // Platforms
            "github.com", "api.github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
            "cloudflare.com", "one.one.one.one", "apple.com", "www.apple.com", "microsoft.com",
            "login.microsoftonline.com", "windowsupdate.com", "outlook.office365.com", "teams.microsoft.com",
            "amazon.com", "www.amazon.com", "en.wikipedia.org", "wikipedia.org", "dropbox.com", "zoom.us",
            "paypal.com", "www.paypal.com",
        )

        /** Shared hosting roots: blocking these would take down unrelated sites. */
        val SHARED_ROOTS = listOf(
            "weebly.com", "github.io", "blogspot.com", "translate.goog", "firebaseapp.com", "web.app", "pages.dev",
            "workers.dev", "appspot.com", "azurewebsites.net", "cloudfront.net", "amazonaws.com", "s3.amazonaws.com",
            "akamaihd.net", "akamaized.net", "googleusercontent.com", "wixsite.com", "wordpress.com", "netlify.app",
            "vercel.app", "herokuapp.com", "gitlab.io", "sites.google.com", "docs.google.com", "storage.googleapis.com",
            "googleapis.com", "gstatic.com", "fbcdn.net", "cdninstagram.com",
        )
    }

    @Test fun essentialDomainsAreNotBlockedInAggressiveMode() {
        val blocked = MUST_NOT_BLOCK.mapNotNull { d ->
            val v = snapshot.evaluate(d, FilterMode.AGGRESSIVE, allCategories)
            if (v.blocked) "$d (rule ${v.matchedRule} in ${v.listId})" else null
        }
        assertTrue("Essential domains blocked by bundled lists:\n" + blocked.joinToString("\n"), blocked.isEmpty())
    }

    @Test fun sharedHostingRootsAreNotListed() {
        val listed = SHARED_ROOTS.filter { snapshot.evaluate(it, FilterMode.AGGRESSIVE, allCategories).blocked }
        assertTrue("Shared roots blocked: $listed", listed.isEmpty())
    }

    @Test fun knownAdAndTrackingDomainsAreBlocked() {
        val expected = mapOf(
            "googleads.g.doubleclick.net" to FilterMode.BALANCED,
            "pagead2.googlesyndication.com" to FilterMode.BALANCED,
            "ads.mopub.com" to FilterMode.BALANCED,
            "an.facebook.com" to FilterMode.BALANCED,
            "auction.unityads.unity3d.com" to FilterMode.BALANCED,
            "config.unityads.unity3d.com" to FilterMode.BALANCED,
            "d.applovin.com" to FilterMode.BALANCED,
            "outcome-ssp.supersonicads.com" to FilterMode.BALANCED,
            "api.vungle.com" to FilterMode.BALANCED,
            "live.chartboost.com" to FilterMode.BALANCED,
            "adservice.google.com" to FilterMode.BALANCED,
            "aax.amazon-adsystem.com" to FilterMode.BALANCED,
            "app-measurement.com" to FilterMode.AGGRESSIVE,
            "www.google-analytics.com" to FilterMode.AGGRESSIVE,
            "app.adjust.com" to FilterMode.AGGRESSIVE,
            "t.appsflyer.com" to FilterMode.AGGRESSIVE,
            "api.mixpanel.com" to FilterMode.AGGRESSIVE,
            "settings.crashlytics.com" to FilterMode.AGGRESSIVE,
            "o123.ingest.sentry.io" to FilterMode.AGGRESSIVE,
        )
        val missed = expected.filter { (d, mode) -> !snapshot.evaluate(d, mode, allCategories).blocked }.keys
        assertTrue("Expected to be blocked: $missed", missed.isEmpty())
    }

    @Test fun analyticsAndTelemetryAreSeparateCategories() {
        for ((asset, cat) in listOf("advault-analytics.txt" to FilterCategory.ANALYTICS, "advault-telemetry.txt" to FilterCategory.TELEMETRY)) {
            val hosts = File(assetDir, asset).readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            val only = 1 shl cat.ordinal
            val without = allCategories and only.inv()
            for (h in hosts) {
                val v = snapshot.evaluate("x.$h", FilterMode.AGGRESSIVE, only)
                assertTrue("$h should be blocked with only $cat enabled", v.blocked)
                assertEquals(cat, v.category)
                // Never blocked in BALANCED mode through this category.
                assertFalse(snapshot.evaluate("x.$h", FilterMode.BALANCED, only).blocked)
            }
            val freed = hosts.count { !snapshot.evaluate("x.$it", FilterMode.AGGRESSIVE, without).blocked }
            println("$cat: ${hosts.size} curated hosts, $freed unblocked when $cat is disabled")
            assertEquals("disabling $cat must unblock its curated hosts", hosts.size, freed)
        }
        assertTrue(snapshot.domainsPerCategory.keys.containsAll(FilterCategory.entries))
    }

    @Test fun bundledListsAreLargeAndAllParse() {
        BuiltInLists.all.forEach { assertTrue("${it.id} empty", (counts[it.id] ?: 0) > 0) }
        assertTrue("expected >= 100k unique domains, got ${snapshot.totalDomains}", snapshot.totalDomains >= 100_000)
        // Every list must be validly attributed and have an HTTPS URL or be AdVault-curated.
        BuiltInLists.all.forEach {
            assertTrue(it.attribution.isNotBlank())
            assertTrue(it.url == null || it.url!!.startsWith("https://"))
            assertTrue(File(assetDir, it.asset).isFile)
        }
        assertEquals(BuiltInLists.all.size, BuiltInLists.all.map { it.id }.toSet().size)
    }

    @Test fun realListLookupThroughput() {
        val queries = MUST_NOT_BLOCK + listOf("googleads.g.doubleclick.net", "a.b.c.d.example.org", "ads.mopub.com", "cdn.example.net")
        val arr = queries.toTypedArray()
        var sink = 0
        repeat(200_000) { i -> if (snapshot.evaluate(arr[i % arr.size], FilterMode.AGGRESSIVE, allCategories).blocked) sink++ }
        val n = 2_000_000
        val t0 = System.nanoTime()
        for (i in 0 until n) if (snapshot.evaluate(arr[i % arr.size], FilterMode.AGGRESSIVE, allCategories).blocked) sink++
        val perSec = n / ((System.nanoTime() - t0) / 1e9)
        println("Real lists evaluate(): ${"%.2f".format(perSec / 1e6)} M lookups/s (sink=$sink)")
        assertTrue("too slow: $perSec/s", perSec > 500_000)
    }
}
