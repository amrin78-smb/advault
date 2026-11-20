package com.advault.intelligence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.advault.intelligence.provider.DefaultProviderRepository
import com.advault.intelligence.provider.ProviderDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderRepositoryTest {
    private lateinit var context: Context
    private val overrideFile get() = File(File(context.filesDir, "intelligence"), "providers.json")
    private val bundledVersion = TestData.bundled().dataVersion

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        overrideFile.delete()
    }

    private fun repo(body: () -> String = { throw IOException("offline") }) =
        DefaultProviderRepository(context, Dispatchers.IO, ProviderDownloader { url, _ ->
            assertEquals(DefaultProviderRepository.UPDATE_URL, url); body()
        })

    @Test fun loadsBundledAsset() = runBlocking {
        val r = repo()
        assertNull(r.match("googleads.g.doubleclick.net"))
        r.load()
        assertEquals("bundled", r.info.value?.source)
        assertEquals("google_admob", r.match("googleads.g.doubleclick.net")?.provider?.id)
        assertTrue(r.providers.value.isNotEmpty())
    }

    @Test fun newerValidOverrideIsPreferred() = runBlocking {
        overrideFile.parentFile!!.mkdirs()
        overrideFile.writeText(TestData.dbJson(dataVersion = "2099.1.1.1"))
        val r = repo(); r.load()
        assertEquals("2099.1.1.1", r.info.value?.dataVersion)
        assertEquals("example", r.match("x.ads.example.com")?.provider?.id)
    }

    @Test fun olderOrInvalidOverrideIsIgnoredAndDeleted() = runBlocking {
        overrideFile.parentFile!!.mkdirs()
        overrideFile.writeText(TestData.dbJson(dataVersion = "2000.1.1.1"))
        repo().also { it.load() }.let { assertEquals(bundledVersion, it.info.value?.dataVersion) }
        assertFalse(overrideFile.exists())
        overrideFile.writeText("{ broken")
        repo().also { it.load() }.let { assertEquals(bundledVersion, it.info.value?.dataVersion) }
        assertFalse(overrideFile.exists())
    }

    @Test fun updateValidatesAndStoresAtomically() = runBlocking {
        assertTrue(repo { "{ not json" }.update().isFailure)
        assertFalse(overrideFile.exists())
        assertTrue(repo { TestData.dbJson(dataVersion = "2099.1.1.1", domainsJson = """[{"pattern":"bad domain","category":"ADVERTISING"}]""") }.update().isFailure)
        assertTrue(repo().update().isFailure) // network failure
        // Older data: success, but nothing replaced.
        val old = repo { TestData.dbJson(dataVersion = "2000.1.1.1") }.update()
        assertEquals(bundledVersion, old.getOrThrow().dataVersion)
        assertFalse(overrideFile.exists())
        val r = repo { TestData.dbJson(dataVersion = "2099.1.1.1") }
        val res = r.update()
        assertEquals("2099.1.1.1", res.getOrThrow().dataVersion)
        assertTrue(overrideFile.exists())
        assertNotNull(r.match("ads.example.com"))
        // A fresh repository picks up the stored update.
        repo().also { it.load() }.let { assertEquals("2099.1.1.1", it.info.value?.dataVersion) }
    }
}
