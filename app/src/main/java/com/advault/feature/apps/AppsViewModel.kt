package com.advault.feature.apps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.advault.R
import com.advault.core.common.AppProtection
import com.advault.core.common.FilterMode
import com.advault.core.database.ActivityLogEntity
import com.advault.data.models.AdProvider
import com.advault.data.models.InstalledApp
import com.advault.data.models.ProtectionState
import com.advault.data.repository.AppRepository
import com.advault.data.repository.ProtectionController
import com.advault.data.repository.ProviderRepository
import com.advault.data.repository.StatsRepository
import com.advault.feature.common.SettingsSource
import com.advault.feature.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppFilter { ALL, USER, GAMES, EXCLUDED }
enum class AppSort { NAME, BLOCKED, QUERIES }

data class AppQuery(
    val search: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val showSystem: Boolean = false,
    val sort: AppSort = AppSort.NAME,
)

/** Pure search / filter / sort logic for the application list. */
object AppListLogic {
    fun apply(apps: List<InstalledApp>, query: AppQuery): List<InstalledApp> {
        val needle = query.search.trim()
        return apps.asSequence()
            .filter { app ->
                when (query.filter) {
                    AppFilter.ALL -> query.showSystem || !app.isSystem
                    AppFilter.USER -> !app.isSystem
                    AppFilter.GAMES -> app.isGame && (query.showSystem || !app.isSystem)
                    // Excluded apps are always listed so a hidden system exclusion can't be forgotten.
                    AppFilter.EXCLUDED -> app.protection == AppProtection.EXCLUDED
                }
            }
            .filter { app ->
                needle.isEmpty() ||
                    app.label.contains(needle, ignoreCase = true) ||
                    app.packageName.contains(needle, ignoreCase = true)
            }
            .sortedWith(
                when (query.sort) {
                    AppSort.NAME -> compareBy<InstalledApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.packageName }
                    AppSort.BLOCKED -> compareByDescending<InstalledApp> { it.blockedQueries }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    AppSort.QUERIES -> compareByDescending<InstalledApp> { it.totalQueries }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                },
            )
            .toList()
    }

    /** Toggle target for the quick switch: excluded apps become protected (default), others become excluded. */
    fun quickToggleTarget(current: AppProtection): AppProtection =
        if (current == AppProtection.EXCLUDED) AppProtection.DEFAULT else AppProtection.EXCLUDED

    /** VPN routing only changes when an app enters or leaves the excluded set. */
    fun routingChanges(from: AppProtection, to: AppProtection): Boolean =
        (from == AppProtection.EXCLUDED) != (to == AppProtection.EXCLUDED)

    fun sdkNames(app: InstalledApp, providers: Map<String, AdProvider>): List<String> =
        app.detectedSdkProviderIds.map { id -> providers[id]?.name ?: id }
}

data class AppRowUi(val app: InstalledApp, val sdkNames: List<String>)

data class AppsUiState(
    val loading: Boolean = true,
    val query: AppQuery = AppQuery(),
    val rows: List<AppRowUi> = emptyList(),
    val totalApps: Int = 0,
    val excludedCount: Int = 0,
    val defaultMode: FilterMode = FilterMode.AGGRESSIVE,
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val appRepository: AppRepository,
    providerRepository: ProviderRepository,
    private val controller: ProtectionController,
    settingsSource: SettingsSource,
) : ViewModel() {

    private val query = MutableStateFlow(
        AppQuery(filter = savedStateHandle.get<String>("filter")?.let { f -> AppFilter.entries.firstOrNull { it.name == f } } ?: AppFilter.ALL),
    )
    private val messages = Channel<UiText>(Channel.BUFFERED)
    val messageFlow: Flow<UiText> = messages.receiveAsFlow()

    val state: StateFlow<AppsUiState> = combine(
        appRepository.observeApps().map<List<InstalledApp>, List<InstalledApp>?> { it }.onStart { emit(null) },
        providerRepository.providers,
        query,
        settingsSource.settings().map { it.defaultMode }.onStart { emit(FilterMode.AGGRESSIVE) },
    ) { apps, providers, q, mode ->
        val byId = providers.associateBy { it.id }
        val list = apps.orEmpty()
        AppsUiState(
            loading = apps == null,
            query = q,
            rows = AppListLogic.apply(list, q).map { AppRowUi(it, AppListLogic.sdkNames(it, byId)) },
            totalApps = list.size,
            excludedCount = list.count { it.protection == AppProtection.EXCLUDED },
            defaultMode = mode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState(query = query.value))

    fun setSearch(text: String) = query.update { it.copy(search = text) }
    fun setFilter(filter: AppFilter) = query.update { it.copy(filter = filter) }
    fun setShowSystem(show: Boolean) = query.update { it.copy(showSystem = show) }
    fun setSort(sort: AppSort) = query.update { it.copy(sort = sort) }

    fun quickToggle(app: InstalledApp) = setProtection(app, AppListLogic.quickToggleTarget(app.protection))

    fun setProtection(app: InstalledApp, protection: AppProtection) {
        if (app.protection == protection) return
        viewModelScope.launch {
            appRepository.setProtection(app.packageName, protection)
            if (AppListLogic.routingChanges(app.protection, protection)) {
                controller.reload()
                if (controller.state.value is ProtectionState.Running) messages.send(UiText.Res(R.string.apps_reconnecting))
            }
        }
    }
}

data class AppDetailUiState(
    val loading: Boolean = true,
    val app: InstalledApp? = null,
    val sdks: List<AdProvider> = emptyList(),
    val unknownSdkIds: List<String> = emptyList(),
    val activity: List<ActivityLogEntity> = emptyList(),
    val providerNames: Map<String, String> = emptyMap(),
    val defaultMode: FilterMode = FilterMode.AGGRESSIVE,
)

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val appRepository: AppRepository,
    providerRepository: ProviderRepository,
    stats: StatsRepository,
    private val controller: ProtectionController,
    settingsSource: SettingsSource,
) : ViewModel() {
    val packageName: String = checkNotNull(savedStateHandle.get<String>("packageName"))

    private val messages = Channel<UiText>(Channel.BUFFERED)
    val messageFlow: Flow<UiText> = messages.receiveAsFlow()

    val state: StateFlow<AppDetailUiState> = combine(
        appRepository.observeApps().map<List<InstalledApp>, InstalledApp?> { list -> list.firstOrNull { it.packageName == packageName } }
            .map<InstalledApp?, Pair<Boolean, InstalledApp?>> { false to it }
            .onStart { emit(true to null) },
        stats.observeActivity(packageName, null, blockedOnly = false, limit = 200).onStart { emit(emptyList()) },
        providerRepository.providers,
        settingsSource.settings().map { it.defaultMode }.onStart { emit(FilterMode.AGGRESSIVE) },
    ) { (loading, app), activity, providers, mode ->
        val byId = providers.associateBy { it.id }
        AppDetailUiState(
            loading = loading,
            app = app,
            sdks = app?.detectedSdkProviderIds?.mapNotNull { byId[it] }.orEmpty(),
            unknownSdkIds = app?.detectedSdkProviderIds?.filter { it !in byId }.orEmpty(),
            activity = activity,
            providerNames = providers.associate { it.id to it.name },
            defaultMode = mode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppDetailUiState())

    fun setProtection(protection: AppProtection) {
        val app = state.value.app ?: return
        if (app.protection == protection) return
        viewModelScope.launch {
            appRepository.setProtection(packageName, protection)
            if (AppListLogic.routingChanges(app.protection, protection)) {
                controller.reload()
                if (controller.state.value is ProtectionState.Running) messages.send(UiText.Res(R.string.apps_reconnecting))
            }
        }
    }
}
