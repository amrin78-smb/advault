@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.advault.feature.apps

import androidx.lifecycle.SavedStateHandle
import com.advault.core.common.AppProtection
import com.advault.feature.FakeAppRepository
import com.advault.feature.FakeProtectionController
import com.advault.feature.FakeProviderRepository
import com.advault.feature.FakeSettingsSource
import com.advault.feature.MainDispatcherRule
import com.advault.feature.app
import com.advault.feature.provider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppListLogicTest {
    private val apps = listOf(
        app("com.example.chat", "Chat", queries = 50, blocked = 5),
        app("com.games.puzzle", "Puzzle Quest", game = true, queries = 900, blocked = 400, sdks = listOf("unity")),
        app("com.android.systemui", "System UI", system = true, queries = 10),
        app("com.games.system", "Preinstalled Game", system = true, game = true),
        app("com.example.bank", "bank", protection = AppProtection.EXCLUDED),
        app("com.android.hidden", "Hidden Excluded", system = true, protection = AppProtection.EXCLUDED),
    )

    private fun names(q: AppQuery) = AppListLogic.apply(apps, q).map { it.label }

    @Test fun allHidesSystemByDefaultAndSortsByName() {
        assertEquals(listOf("bank", "Chat", "Puzzle Quest"), names(AppQuery()))
    }

    @Test fun allWithSystem() {
        assertEquals(6, names(AppQuery(showSystem = true)).size)
    }

    @Test fun userFilterIgnoresSystemToggle() {
        assertEquals(listOf("bank", "Chat", "Puzzle Quest"), names(AppQuery(filter = AppFilter.USER, showSystem = true)))
    }

    @Test fun gamesFilter() {
        assertEquals(listOf("Puzzle Quest"), names(AppQuery(filter = AppFilter.GAMES)))
        assertEquals(listOf("Preinstalled Game", "Puzzle Quest"), names(AppQuery(filter = AppFilter.GAMES, showSystem = true)))
    }

    @Test fun excludedFilterAlwaysShowsSystemExclusions() {
        assertEquals(listOf("bank", "Hidden Excluded"), names(AppQuery(filter = AppFilter.EXCLUDED)))
    }

    @Test fun searchMatchesLabelAndPackageCaseInsensitive() {
        assertEquals(listOf("Puzzle Quest"), names(AppQuery(search = "PUZZLE")))
        assertEquals(listOf("bank", "Chat"), names(AppQuery(search = "com.example")))
        assertEquals(listOf("Chat"), names(AppQuery(search = "  chat ")))
        assertTrue(names(AppQuery(search = "nothing-matches")).isEmpty())
    }

    @Test fun searchCombinesWithFilter() {
        assertEquals(emptyList<String>(), names(AppQuery(search = "chat", filter = AppFilter.GAMES)))
    }

    @Test fun sortByBlockedAndQueries() {
        assertEquals("Puzzle Quest", names(AppQuery(sort = AppSort.BLOCKED)).first())
        assertEquals(listOf("Puzzle Quest", "Chat", "bank"), names(AppQuery(sort = AppSort.QUERIES)))
    }

    @Test fun quickToggleAndRouting() {
        assertEquals(AppProtection.EXCLUDED, AppListLogic.quickToggleTarget(AppProtection.DEFAULT))
        assertEquals(AppProtection.EXCLUDED, AppListLogic.quickToggleTarget(AppProtection.BALANCED))
        assertEquals(AppProtection.DEFAULT, AppListLogic.quickToggleTarget(AppProtection.EXCLUDED))
        assertTrue(AppListLogic.routingChanges(AppProtection.DEFAULT, AppProtection.EXCLUDED))
        assertTrue(AppListLogic.routingChanges(AppProtection.EXCLUDED, AppProtection.AGGRESSIVE))
        assertFalse(AppListLogic.routingChanges(AppProtection.BALANCED, AppProtection.AGGRESSIVE))
    }

    @Test fun sdkNamesResolveFromProviders() {
        val a = app("x.y", sdks = listOf("unity", "unknown_sdk"))
        assertEquals(listOf("Unity Ads", "unknown_sdk"), AppListLogic.sdkNames(a, mapOf("unity" to provider("unity", "Unity Ads"))))
    }
}

class AppsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeAppRepository(
        listOf(app("com.a", "Alpha"), app("com.g", "Game", game = true, sdks = listOf("applovin")), app("com.s", "Sys", system = true)),
    )
    private val controller = FakeProtectionController()

    private fun vm(filter: String? = null) = AppsViewModel(
        SavedStateHandle(if (filter != null) mapOf("filter" to filter) else emptyMap()),
        repo, FakeProviderRepository(listOf(provider("applovin", "AppLovin"))), controller, FakeSettingsSource(),
    )

    @Test fun searchAndFilterFlowIntoState() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertFalse(vm.state.value.loading)
        assertEquals(listOf("Alpha", "Game"), vm.state.value.rows.map { it.app.label })
        assertEquals(listOf("AppLovin"), vm.state.value.rows.first { it.app.packageName == "com.g" }.sdkNames)
        vm.setFilter(AppFilter.GAMES)
        assertEquals(listOf("Game"), vm.state.value.rows.map { it.app.label })
        vm.setFilter(AppFilter.ALL)
        vm.setShowSystem(true)
        vm.setSearch("sys")
        assertEquals(listOf("Sys"), vm.state.value.rows.map { it.app.label })
        assertEquals(3, vm.state.value.totalApps)
    }

    @Test fun initialFilterFromNavigationArgument() = runTest {
        val vm = vm("EXCLUDED")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(AppFilter.EXCLUDED, vm.state.value.query.filter)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun excludingReloadsVpnButModeChangeDoesNot() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        val alpha = vm.state.value.rows.first().app
        vm.quickToggle(alpha)
        assertEquals("com.a" to AppProtection.EXCLUDED, repo.protectionCalls.last())
        assertEquals(1, controller.reloadCalls)
        assertEquals(1, vm.state.value.excludedCount)

        val game = vm.state.value.rows.first { it.app.packageName == "com.g" }.app
        vm.setProtection(game, AppProtection.BALANCED)
        assertEquals(1, controller.reloadCalls)
    }
}
