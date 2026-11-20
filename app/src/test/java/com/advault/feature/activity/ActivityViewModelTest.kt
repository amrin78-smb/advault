@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.advault.feature.activity

import androidx.lifecycle.SavedStateHandle
import com.advault.core.common.Decision
import com.advault.core.common.DecisionReason
import com.advault.core.database.ActivityLogEntity
import com.advault.core.preferences.AppSettings
import com.advault.feature.FakeAppRepository
import com.advault.feature.FakeProviderRepository
import com.advault.feature.FakeSettingsSource
import com.advault.feature.FakeStatsRepository
import com.advault.feature.MainDispatcherRule
import com.advault.feature.app
import com.advault.feature.provider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ActivityViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun entry(id: Long, ts: Long, pkg: String?, domain: String, blocked: Boolean, provider: String? = null) = ActivityLogEntity(
        id = id, timestamp = ts, uid = 1, packageName = pkg, domain = domain, queryType = 1,
        decision = if (blocked) Decision.BLOCKED else Decision.ALLOWED,
        reason = if (blocked) DecisionReason.BLOCKLIST else DecisionReason.NO_MATCH,
        category = null, providerId = provider, listId = null, latencyMs = 0, cached = false,
    )

    private val stats = FakeStatsRepository().apply {
        activity.value = listOf(
            entry(1, 100, "com.game", "ads.unity3d.com", true, "unity"),
            entry(2, 300, "com.chat", "chat.example.com", false),
            entry(3, 200, null, "tracker.example", true, "mystery"),
            entry(4, 400, "com.gone", "x.example", false),
        )
        packages.value = listOf("com.game", "com.chat")
        providers.value = listOf("unity")
    }
    private val settings = FakeSettingsSource()

    private fun vm(pkg: String? = null) = ActivityViewModel(
        SavedStateHandle(if (pkg != null) mapOf("packageName" to pkg) else emptyMap()),
        stats,
        FakeAppRepository(listOf(app("com.game", "Puzzle Quest"), app("com.chat", "Chat"))),
        FakeProviderRepository(listOf(provider("unity", "Unity Ads"))),
        settings,
    )

    @Test fun rowsAreNewestFirstWithResolvedLabelsAndProviders() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        val rows = vm.state.value.rows
        assertEquals(listOf(4L, 2L, 3L, 1L), rows.map { it.id })
        val game = rows.first { it.id == 1L }
        assertEquals("Puzzle Quest", game.appLabel)
        assertEquals("Unity Ads", game.providerName)
        assertTrue(game.blocked)
        val unknown = rows.first { it.id == 3L }
        assertNull(unknown.packageName)
        assertNull(unknown.appLabel) // UI shows "Unknown app"
        assertEquals("mystery", unknown.providerName) // unknown provider id falls back to id
        assertNull(rows.first { it.id == 4L }.appLabel) // uninstalled app -> package shown by UI
    }

    @Test fun filtersArePassedToRepository() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.setPackage("com.game")
        assertEquals(listOf(1L), vm.state.value.rows.map { it.id })
        vm.setPackage(null)
        vm.setBlockedOnly(true)
        assertEquals(listOf(3L, 1L), vm.state.value.rows.map { it.id })
        vm.setProvider("unity")
        assertEquals(listOf(1L), vm.state.value.rows.map { it.id })
        assertEquals(Triple(null, "unity", true), stats.activityQueries.last())
        assertTrue(vm.state.value.filter.isActive)
        vm.clearFilters()
        assertFalse(vm.state.value.filter.isActive)
        assertEquals(4, vm.state.value.rows.size)
    }

    @Test fun initialPackageFilterFromNavigation() = runTest {
        val vm = vm("com.chat")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(listOf(2L), vm.state.value.rows.map { it.id })
        assertEquals("com.chat", stats.activityQueries.last().first)
    }

    @Test fun filterOptionsUseLabels() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(listOf("Chat", "Puzzle Quest"), vm.state.value.appOptions.map { it.label })
        assertEquals(listOf(FilterOption("unity", "Unity Ads")), vm.state.value.providerOptions)
    }

    @Test fun loggingDisabledIsExposed() = runTest {
        settings.flow.value = AppSettings(activityLoggingEnabled = false)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertFalse(vm.state.value.loggingEnabled)
    }

    @Test fun clearHistory() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.clearHistory()
        assertEquals(1, stats.clearedActivity)
        assertTrue(vm.state.value.rows.isEmpty())
    }
}
