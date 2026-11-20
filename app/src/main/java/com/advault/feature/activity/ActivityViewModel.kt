package com.advault.feature.activity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.advault.R
import com.advault.core.common.Decision
import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.database.ActivityLogEntity
import com.advault.data.repository.AppRepository
import com.advault.data.repository.ProviderRepository
import com.advault.data.repository.StatsRepository
import com.advault.feature.common.SettingsSource
import com.advault.feature.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActivityFilter(
    val packageName: String? = null,
    val providerId: String? = null,
    val blockedOnly: Boolean = false,
) {
    val isActive: Boolean get() = packageName != null || providerId != null || blockedOnly
}

data class ActivityRowUi(
    val id: Long,
    val timestamp: Long,
    val packageName: String?,
    /** Resolved app label; null if the app is unknown or uninstalled. */
    val appLabel: String?,
    val domain: String,
    val providerName: String?,
    val blocked: Boolean,
    val reason: DecisionReason,
    val category: FilterCategory?,
    val queryType: Int,
    val cached: Boolean,
    val latencyMs: Int,
)

data class FilterOption(val id: String, val label: String)

data class ActivityUiState(
    val loading: Boolean = true,
    val loggingEnabled: Boolean = true,
    val retentionDays: Int = 7,
    val filter: ActivityFilter = ActivityFilter(),
    val rows: List<ActivityRowUi> = emptyList(),
    val appOptions: List<FilterOption> = emptyList(),
    val providerOptions: List<FilterOption> = emptyList(),
)

object ActivityMapper {
    fun row(e: ActivityLogEntity, labels: Map<String, String>, providerNames: Map<String, String>) = ActivityRowUi(
        id = e.id,
        timestamp = e.timestamp,
        packageName = e.packageName,
        appLabel = e.packageName?.let { labels[it] },
        domain = e.domain,
        providerName = e.providerId?.let { providerNames[it] ?: it },
        blocked = e.decision == Decision.BLOCKED,
        reason = e.reason,
        category = e.category,
        queryType = e.queryType,
        cached = e.cached,
        latencyMs = e.latencyMs,
    )

    /** Newest first, regardless of the order the repository returns. */
    fun rows(entries: List<ActivityLogEntity>, labels: Map<String, String>, providerNames: Map<String, String>) =
        entries.sortedByDescending { it.timestamp }.map { row(it, labels, providerNames) }

    fun appOptions(packages: List<String>, labels: Map<String, String>) =
        packages.distinct().map { FilterOption(it, labels[it] ?: it) }.sortedBy { it.label.lowercase() }

    fun providerOptions(ids: List<String>, names: Map<String, String>) =
        ids.distinct().map { FilterOption(it, names[it] ?: it) }.sortedBy { it.label.lowercase() }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActivityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stats: StatsRepository,
    appRepository: AppRepository,
    providerRepository: ProviderRepository,
    settingsSource: SettingsSource,
) : ViewModel() {

    private val filter = MutableStateFlow(ActivityFilter(packageName = savedStateHandle.get<String>("packageName")))
    private val messages = Channel<UiText>(Channel.BUFFERED)
    val messageFlow: Flow<UiText> = messages.receiveAsFlow()

    private val labels: Flow<Map<String, String>> = appRepository.observeApps()
        .map { apps -> apps.associate { it.packageName to it.label } }
        .distinctUntilChanged()
        .onStart { emit(emptyMap()) }

    private val providerNames: Flow<Map<String, String>> = providerRepository.providers.map { list -> list.associate { it.id to it.name } }

    private val entries: Flow<Pair<ActivityFilter, List<ActivityLogEntity>?>> = filter.flatMapLatest { f ->
        stats.observeActivity(f.packageName, f.providerId, f.blockedOnly, LIMIT)
            .map<List<ActivityLogEntity>, Pair<ActivityFilter, List<ActivityLogEntity>?>> { f to it }
            .onStart { emit(f to null) }
    }

    private val options: Flow<Pair<List<String>, List<String>>> = combine(
        stats.observeActivityPackages().onStart { emit(emptyList()) },
        stats.observeActivityProviders().onStart { emit(emptyList()) },
        ::Pair,
    )

    val state: StateFlow<ActivityUiState> = combine(
        entries,
        labels,
        providerNames,
        options,
        settingsSource.settings().map { it.activityLoggingEnabled to it.logRetentionDays }.onStart { emit(true to 7) },
    ) { (f, list), lbl, names, (pkgs, provs), (logging, retention) ->
        ActivityUiState(
            loading = list == null,
            loggingEnabled = logging,
            retentionDays = retention,
            filter = f,
            rows = ActivityMapper.rows(list.orEmpty(), lbl, names),
            appOptions = ActivityMapper.appOptions(pkgs, lbl),
            providerOptions = ActivityMapper.providerOptions(provs, names),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(filter = filter.value))

    fun setPackage(packageName: String?) = filter.update { it.copy(packageName = packageName) }
    fun setProvider(providerId: String?) = filter.update { it.copy(providerId = providerId) }
    fun setBlockedOnly(blockedOnly: Boolean) = filter.update { it.copy(blockedOnly = blockedOnly) }
    fun clearFilters() { filter.value = ActivityFilter() }

    fun clearHistory() {
        viewModelScope.launch {
            stats.clearActivity()
            messages.send(UiText.Res(R.string.activity_cleared))
        }
    }

    companion object {
        const val LIMIT = 500
    }
}
