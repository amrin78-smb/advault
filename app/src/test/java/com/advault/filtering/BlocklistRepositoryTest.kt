package com.advault.filtering

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.advault.core.common.AdFormat
import com.advault.core.common.BlocklistFormat
import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.core.common.RuleType
import com.advault.core.common.UpdateFrequency
import com.advault.core.database.AppDatabase
import com.advault.core.database.BlocklistEntity
import com.advault.data.models.AdProvider
import com.advault.data.models.BlocklistUpdateState
import com.advault.data.models.ProviderDomain
import com.advault.filtering.data.BlocklistFileStore
import com.advault.filtering.data.BuiltInLists
import com.advault.filtering.data.DefaultBlocklistRepository
import com.advault.filtering.data.DirectoryBlocklistAssets
import com.advault.filtering.data.UpdateSchedule
import com.advault.filtering.engine.DefaultFilterEngine
import com.advault.filtering.work.BlocklistUpdateWorker
import com.advault.filtering.work.WorkManagerUpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class BlocklistRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var store: BlocklistFileStore
    private val downloader = FakeDownloader()
    private val settings = FakeSettings()
    private val scheduler = FakeScheduler()
    private val providers = FakeProviderRepository()
    private lateinit var repo: DefaultBlocklistRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = BlocklistFileStore(File(context.filesDir, "blocklists-test-${System.nanoTime()}"))
        repo = DefaultBlocklistRepository(
            db.blocklistDao(), db.customRuleDao(), store, DirectoryBlocklistAssets(File("src/main/assets/blocklists")),
            downloader, settings, scheduler, providers, scope, Dispatchers.IO,
        )
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
        store.dir.deleteRecursively()
    }

    private fun userList(id: String = "user-test", url: String = "https://lists.example/list.txt") = BlocklistEntity(
        id = id, name = "Test list", url = url, category = FilterCategory.ADVERTISING, format = BlocklistFormat.DOMAINS,
        enabled = true, builtIn = false, attribution = "test", etag = "\"v1\"", lastModifiedHeader = "Mon, 01 Jan 2026 00:00:00 GMT",
        lastUpdated = 1,
    )

    private fun writeList(id: String, vararg rules: String) = store.writeAtomically(id) { w -> rules.forEach { w.write("$it\n") } }

    @Test fun ensureInitializedInstallsBundledListsAndUpgrades() = runBlocking {
        repo.ensureInitialized()
        val all = db.blocklistDao().getAll().associateBy { it.id }
        for (def in BuiltInLists.all) {
            val e = all.getValue(def.id)
            assertTrue(e.builtIn); assertTrue(e.enabled); assertNull(e.lastError)
            assertTrue("${def.id} rules", e.ruleCount > 0)
            assertEquals(BuiltInLists.SNAPSHOT_EPOCH_MS, e.lastUpdated)
            assertTrue(store.exists(def.id))
        }
        assertTrue(all.getValue("stevenblack-unified").ruleCount > 50_000)
        assertNotNull(all[BuiltInLists.PROVIDER_SOURCE_ID])

        // Second run is a no-op; an older local copy is replaced but the user's enabled choice is kept.
        val sbFile = store.file("adaway")
        val stamp = sbFile.lastModified()
        db.blocklistDao().upsert(all.getValue("adaway").copy(enabled = false, lastUpdated = 5))
        db.blocklistDao().upsert(all.getValue("phishing-filter").copy(lastUpdated = BuiltInLists.SNAPSHOT_EPOCH_MS + 1))
        val phishingStamp = store.file("phishing-filter").lastModified()
        Thread.sleep(20)
        repo.ensureInitialized()
        val adaway = db.blocklistDao().get("adaway")!!
        assertFalse(adaway.enabled)
        assertEquals(BuiltInLists.SNAPSHOT_EPOCH_MS, adaway.lastUpdated)
        assertTrue(store.file("adaway").lastModified() >= stamp)
        assertEquals(phishingStamp, store.file("phishing-filter").lastModified())
        assertEquals(BuiltInLists.SNAPSHOT_EPOCH_MS + 1, db.blocklistDao().get("phishing-filter")!!.lastUpdated)
    }

    @Test fun updateFlowWithConditionalRequestsAndFailures() = runBlocking {
        val e = userList()
        db.blocklistDao().upsert(e)
        writeList(e.id, "old.example.com")

        // 304: conditional headers sent, file untouched.
        downloader.responses[e.url!!] = FakeDownloader.Response.NotModified
        val unchanged = repo.updateList(e.id)
        assertTrue(unchanged.isSuccess)
        assertEquals(FakeDownloader.Request(e.url!!, "\"v1\"", "Mon, 01 Jan 2026 00:00:00 GMT"), downloader.requests.last())
        assertEquals(listOf("old.example.com"), store.file(e.id).readLines())
        assertTrue(db.blocklistDao().get(e.id)!!.lastUpdated > 1)

        // 200: parsed, deduplicated, counted, atomically replaced, new validators stored.
        downloader.responses[e.url!!] = FakeDownloader.Response.Body("0.0.0.0 a.example\n0.0.0.0 b.example\n0.0.0.0 a.example\nnot valid!\n", "\"v2\"", "Tue")
        assertEquals(2, repo.updateList(e.id).getOrThrow())
        val after = db.blocklistDao().get(e.id)!!
        assertEquals(2, after.ruleCount); assertEquals(1, after.duplicateCount); assertEquals(1, after.invalidRuleCount)
        assertEquals("\"v2\"", after.etag); assertEquals("Tue", after.lastModifiedHeader); assertEquals(BlocklistFormat.HOSTS, after.format)
        assertEquals(listOf("a.example", "b.example"), store.file(e.id).readLines())

        // Network failure: old data kept, error recorded.
        downloader.responses[e.url!!] = FakeDownloader.Response.Error("Connection reset")
        assertTrue(repo.updateList(e.id).isFailure)
        assertEquals(listOf("a.example", "b.example"), store.file(e.id).readLines())
        assertEquals("Connection reset", db.blocklistDao().get(e.id)!!.lastError)

        // Garbage (HTML error page served with 200): rejected, old data kept.
        downloader.responses[e.url!!] = FakeDownloader.Response.Body("<!DOCTYPE html><html>Gone</html>")
        assertTrue(repo.updateList(e.id).isFailure)
        assertEquals(listOf("a.example", "b.example"), store.file(e.id).readLines())
        assertTrue(db.blocklistDao().get(e.id)!!.lastError!!.contains("HTML"))
        assertEquals(0, store.dir.listFiles()!!.count { it.name.contains(".tmp-") })

        // Success clears the error; force=true skips conditional headers.
        downloader.responses[e.url!!] = FakeDownloader.Response.Body("c.example\n", "\"v3\"")
        val report = repo.updateAll(force = true)
        assertEquals(listOf(e.id), report.updated)
        assertEquals(FakeDownloader.Request(e.url!!, null, null), downloader.requests.last())
        assertNull(db.blocklistDao().get(e.id)!!.lastError)
        assertEquals(BlocklistUpdateState.Finished(report), repo.updateState.value)
    }

    @Test fun updateAllReportsPerList() = runBlocking {
        val a = userList("user-a", "https://a.example/l")
        val b = userList("user-b", "https://b.example/l")
        val c = userList("user-c", "https://c.example/l")
        val disabled = userList("user-d", "https://d.example/l").copy(enabled = false)
        listOf(a, b, c, disabled).forEach { db.blocklistDao().upsert(it); writeList(it.id, "keep.example") }
        downloader.responses[a.url!!] = FakeDownloader.Response.Body("x.example\n")
        downloader.responses[b.url!!] = FakeDownloader.Response.NotModified
        downloader.responses[c.url!!] = FakeDownloader.Response.Error("HTTP 500")
        val r = repo.updateAll()
        assertEquals(listOf("user-a"), r.updated)
        assertEquals(listOf("user-b"), r.unchanged)
        assertEquals(mapOf("user-c" to "HTTP 500"), r.failed)
        assertTrue(downloader.requests.none { it.url == disabled.url })
    }

    @Test fun addCustomListValidatesAndDownloads() = runBlocking {
        assertTrue(repo.addCustomList("x", "http://insecure.example/list", FilterCategory.ADVERTISING).isFailure)
        assertTrue(repo.addCustomList("x", "ftp://x.example/list", FilterCategory.ADVERTISING).isFailure)
        assertTrue(repo.addCustomList("", "https://x.example/list", FilterCategory.ADVERTISING).isFailure)

        downloader.responses["https://bad.example/list"] = FakeDownloader.Response.Error("HTTP 404")
        val bad = repo.addCustomList("Bad", "https://bad.example/list", FilterCategory.ADVERTISING)
        assertTrue(bad.isFailure)
        assertTrue(db.blocklistDao().getAll().isEmpty())

        downloader.responses["https://good.example/list"] = FakeDownloader.Response.Body("||ads.good.example^\n||trk.good.example^\n", "\"e\"")
        val ok = repo.addCustomList("Good", "https://good.example/list", FilterCategory.TRACKING).getOrThrow()
        assertEquals(2, ok.ruleCount); assertEquals(BlocklistFormat.ADBLOCK, ok.format); assertFalse(ok.builtIn)
        assertEquals(FilterCategory.TRACKING, ok.category); assertEquals("\"e\"", ok.etag)
        assertTrue(store.exists(ok.id))
        assertTrue(repo.addCustomList("Again", "https://good.example/list", FilterCategory.TRACKING).isFailure)

        repo.removeCustomList(ok.id)
        assertNull(db.blocklistDao().get(ok.id)); assertFalse(store.exists(ok.id))
    }

    @Test fun builtInListsCannotBeRemoved() = runBlocking {
        db.blocklistDao().upsert(BuiltInLists.all.first().toEntity())
        writeList(BuiltInLists.all.first().id, "a.example")
        repo.removeCustomList(BuiltInLists.all.first().id)
        assertNotNull(db.blocklistDao().get(BuiltInLists.all.first().id))
        assertTrue(store.exists(BuiltInLists.all.first().id))
    }

    @Test fun customRules() = runBlocking {
        val r = repo.addCustomRule("https://Ads.Example.com/x", RuleType.BLOCK).getOrThrow()
        assertEquals("ads.example.com", r.domain); assertTrue(r.id > 0)
        assertTrue(repo.addCustomRule("ads.example.com", RuleType.BLOCK).isFailure) // duplicate
        assertTrue(repo.addCustomRule("ads.example.com", RuleType.ALLOW).isSuccess) // same domain, other type is allowed
        assertEquals("*.cdn.example", repo.addCustomRule("*.cdn.example", RuleType.ALLOW).getOrThrow().domain)
        assertTrue(repo.addCustomRule("10.0.0.1", RuleType.BLOCK).isFailure)
        assertTrue(repo.addCustomRule("nodot", RuleType.BLOCK).isFailure)
        repo.removeCustomRule(r.id)
        assertEquals(2, db.customRuleDao().getAll().size)
    }

    @Test fun scheduleIsAppliedAndFollowsSettings() = runBlocking {
        repo.applyUpdateSchedule()
        assertEquals(UpdateSchedule(UpdateFrequency.DAILY, false), scheduler.applied.last())
        settings.updateSchedule.value = UpdateSchedule(UpdateFrequency.WEEKLY, true)
        withTimeout(5_000) { while (scheduler.applied.last() != settings.updateSchedule.value) delay(20) }
    }

    @Test fun workManagerSchedulerEnqueuesAndCancels() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val wm = WorkManager.getInstance(context)
        val s = WorkManagerUpdateScheduler(context)
        s.apply(UpdateSchedule(UpdateFrequency.EVERY_3_DAYS, unmeteredOnly = true))
        val info = wm.getWorkInfosForUniqueWork(BlocklistUpdateWorker.UNIQUE_NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertEquals(72L * 3600 * 1000, info.periodicityInfo!!.repeatIntervalMillis)
        s.apply(UpdateSchedule(UpdateFrequency.DAILY, unmeteredOnly = false))
        val updated = wm.getWorkInfosForUniqueWork(BlocklistUpdateWorker.UNIQUE_NAME).get().single()
        assertEquals(NetworkType.CONNECTED, updated.constraints.requiredNetworkType)
        s.apply(UpdateSchedule(UpdateFrequency.MANUAL, unmeteredOnly = false))
        val cancelled = wm.getWorkInfosForUniqueWork(BlocklistUpdateWorker.UNIQUE_NAME).get()
        assertTrue(cancelled.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test fun engineLoadsListsRulesProvidersAndReloadsAutomatically() = runBlocking {
        val engine = DefaultFilterEngine(
            db.blocklistDao(), db.customRuleDao(), providers, settings, store,
            DirectoryBlocklistAssets(File("src/main/assets/blocklists")), scope, Dispatchers.IO,
        )
        assertEquals(DecisionReason.NO_MATCH, engine.evaluate("ads.example.com", FilterMode.AGGRESSIVE).reason) // fail open before load

        db.blocklistDao().upsert(userList("user-ads").copy(ruleCount = 2))
        writeList("user-ads", "ads.example.com", "*.wild.example")
        db.blocklistDao().upsert(userList("user-trk", "https://t.example/l").copy(category = FilterCategory.TRACKING))
        writeList("user-trk", "tracker.example.com", "graph.facebook.com")
        repo.addCustomRule("allowme.ads.example.com", RuleType.ALLOW)
        repo.addCustomRule("blockme.org", RuleType.BLOCK)
        providers.providers.value = listOf(
            AdProvider("unity", "Unity Ads", "", "", listOf(ProviderDomain("unityads.unity3d.com", FilterCategory.GAME_ADS, listOf(AdFormat.REWARDED))),
                emptyList(), true),
        )
        engine.reload()

        val st = engine.status.value
        assertTrue(st.loaded); assertFalse(st.loading)
        assertEquals(5, st.totalDomains)
        assertEquals(1, st.customAllowRules); assertEquals(1, st.customBlockRules)
        assertEquals(mapOf(FilterCategory.ADVERTISING to 2, FilterCategory.TRACKING to 2, FilterCategory.GAME_ADS to 1), st.domainsPerCategory)

        val v = engine.evaluate("x.ads.example.com", FilterMode.BALANCED)
        assertTrue(v.blocked); assertEquals("user-ads", v.listId); assertEquals("ads.example.com", v.matchedRule)
        assertTrue(engine.evaluate("a.wild.example", FilterMode.AGGRESSIVE).blocked)
        assertFalse(engine.evaluate("wild.example", FilterMode.AGGRESSIVE).blocked)
        assertEquals(DecisionReason.CUSTOM_ALLOW, engine.evaluate("allowme.ads.example.com", FilterMode.AGGRESSIVE).reason)
        assertEquals(DecisionReason.CUSTOM_BLOCK, engine.evaluate("blockme.org", FilterMode.AGGRESSIVE).reason)
        assertEquals("provider-intelligence", engine.evaluate("auction.unityads.unity3d.com", FilterMode.BALANCED).listId)
        // builtin_allow.txt protects graph.facebook.com even though a list contains it.
        assertFalse(engine.evaluate("graph.facebook.com", FilterMode.AGGRESSIVE).blocked)
        assertEquals(DecisionReason.MODE_EXCLUDES_CATEGORY, engine.evaluate("tracker.example.com", FilterMode.BALANCED).reason)

        // Category toggle applies without a rebuild.
        settings.enabledCategories.value = FilterCategory.entries.toSet() - FilterCategory.ADVERTISING
        withTimeout(5_000) { while (engine.evaluate("ads.example.com", FilterMode.AGGRESSIVE).blocked) delay(10) }
        assertEquals(DecisionReason.CATEGORY_DISABLED, engine.evaluate("ads.example.com", FilterMode.AGGRESSIVE).reason)
        settings.enabledCategories.value = FilterCategory.entries.toSet()

        // Disabling a list, adding a rule and changing provider data trigger a debounced reload.
        repo.setEnabled("user-trk", false)
        repo.addCustomRule("late.example", RuleType.BLOCK)
        withTimeout(10_000) {
            while (engine.evaluate("tracker.example.com", FilterMode.AGGRESSIVE).blocked ||
                !engine.evaluate("late.example", FilterMode.AGGRESSIVE).blocked) delay(50)
        }
        providers.providers.value = emptyList()
        withTimeout(10_000) { while (engine.evaluate("unityads.unity3d.com", FilterMode.AGGRESSIVE).blocked) delay(50) }
        assertEquals(2, engine.status.value.totalDomains)
    }
}
