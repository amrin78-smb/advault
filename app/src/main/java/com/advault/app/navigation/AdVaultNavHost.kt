package com.advault.app.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.advault.R
import com.advault.core.preferences.SettingsRepository
import com.advault.feature.activity.ActivityRoute
import com.advault.feature.apps.AppDetailRoute
import com.advault.feature.apps.AppFilter
import com.advault.feature.apps.AppsRoute
import com.advault.feature.blocklists.BlocklistsRoute
import com.advault.feature.blocklists.CustomRulesRoute
import com.advault.feature.dashboard.DashboardRoute
import com.advault.feature.onboarding.OnboardingScreen
import com.advault.feature.rewards.ProviderDetailRoute
import com.advault.feature.rewards.RewardsRoute
import com.advault.feature.settings.AboutRoute
import com.advault.feature.settings.DiagnosticsRoute
import com.advault.feature.settings.SettingsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import kotlin.reflect.KClass

// ---------------------------------------------------------------- Routes (type-safe navigation)

@Serializable data object DashboardDest
/** [filter] is an [AppFilter] name, e.g. "EXCLUDED" for the Settings shortcut. */
@Serializable data class AppsDest(val filter: String? = null)
/** [packageName] pre-filters the log (from app detail). */
@Serializable data class ActivityDest(val packageName: String? = null)
@Serializable data object RewardsDest
@Serializable data object SettingsDest
@Serializable data object BlocklistsDest
@Serializable data object CustomRulesDest
@Serializable data class AppDetailDest(val packageName: String)
@Serializable data class ProviderDetailDest(val providerId: String)
@Serializable data object DiagnosticsDest
@Serializable data object AboutDest

private data class TopLevel(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val topLevel = listOf(
    TopLevel(DashboardDest, DashboardDest::class, R.string.nav_dashboard, Icons.Outlined.Shield, Icons.Filled.Shield),
    TopLevel(AppsDest(), AppsDest::class, R.string.nav_apps, Icons.Outlined.Apps, Icons.Filled.Apps),
    TopLevel(ActivityDest(), ActivityDest::class, R.string.nav_activity, Icons.AutoMirrored.Outlined.ListAlt, Icons.AutoMirrored.Filled.ListAlt),
    TopLevel(RewardsDest, RewardsDest::class, R.string.nav_rewards, Icons.Outlined.EmojiEvents, Icons.Filled.EmojiEvents),
    TopLevel(SettingsDest, SettingsDest::class, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

@HiltViewModel
class RootViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    /** null while loading (avoids flashing onboarding for returning users). */
    val onboardingComplete: StateFlow<Boolean?> = settings.settings.map<_, Boolean?> { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun completeOnboarding() {
        viewModelScope.launch { settings.setOnboardingComplete(true) }
    }
}

@Composable
fun AdVaultNavHost(rootViewModel: RootViewModel = hiltViewModel()) {
    val onboarded by rootViewModel.onboardingComplete.collectAsStateWithLifecycle()
    when (onboarded) {
        null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        false -> OnboardingScreen(onFinish = rootViewModel::completeOnboarding)
        true -> MainScaffold()
    }
}

@Composable
private fun MainScaffold(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination == null || topLevel.any { t -> destination.hasRoute(t.routeClass) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    topLevel.forEach { item ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(item.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = DashboardDest,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) },
        ) {
            val back: () -> Unit = { navController.popBackStack() }
            composable<DashboardDest> {
                DashboardRoute(
                    onOpenActivity = { navController.navigateTopLevel(ActivityDest()) },
                    onOpenApps = { navController.navigateTopLevel(AppsDest()) },
                    onOpenBlocklists = { navController.navigate(BlocklistsDest) },
                    onOpenDiagnostics = { navController.navigate(DiagnosticsDest) },
                )
            }
            composable<AppsDest> {
                AppsRoute(onOpenApp = { navController.navigate(AppDetailDest(it)) })
            }
            composable<ActivityDest> { entry ->
                val filtered = entry.arguments?.getString("packageName") != null
                ActivityRoute(
                    onOpenSettings = { navController.navigateTopLevel(SettingsDest) },
                    onBack = if (filtered) back else null,
                )
            }
            composable<RewardsDest> {
                RewardsRoute(onOpenProvider = { navController.navigate(ProviderDetailDest(it)) })
            }
            composable<SettingsDest> {
                SettingsRoute(
                    onOpenBlocklists = { navController.navigate(BlocklistsDest) },
                    onOpenExclusions = { navController.navigate(AppsDest(AppFilter.EXCLUDED.name)) },
                    onOpenDiagnostics = { navController.navigate(DiagnosticsDest) },
                    onOpenAbout = { navController.navigate(AboutDest) },
                )
            }
            composable<BlocklistsDest> {
                BlocklistsRoute(onBack = back, onOpenCustomRules = { navController.navigate(CustomRulesDest) })
            }
            composable<CustomRulesDest> { CustomRulesRoute(onBack = back) }
            composable<AppDetailDest> {
                AppDetailRoute(
                    onBack = back,
                    onOpenFullActivity = { navController.navigate(ActivityDest(it)) },
                    onOpenProvider = { navController.navigate(ProviderDetailDest(it)) },
                )
            }
            composable<ProviderDetailDest> { ProviderDetailRoute(onBack = back) }
            composable<DiagnosticsDest> { DiagnosticsRoute(onBack = back) }
            composable<AboutDest> { AboutRoute(onBack = back) }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
