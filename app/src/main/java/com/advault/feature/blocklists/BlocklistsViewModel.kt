package com.advault.feature.blocklists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.advault.R
import com.advault.core.common.FilterCategory
import com.advault.core.common.RuleType
import com.advault.core.database.BlocklistEntity
import com.advault.core.database.CustomRuleEntity
import com.advault.core.preferences.SettingsRepository
import com.advault.data.models.BlocklistUpdateReport
import com.advault.data.models.BlocklistUpdateState
import com.advault.data.models.FilterEngineStatus
import com.advault.data.repository.BlocklistRepository
import com.advault.data.repository.FilterEngine
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddListDialogState(
    val name: String = "",
    val url: String = "https://",
    val category: FilterCategory = FilterCategory.ADVERTISING,
    val error: UiText? = null,
    val submitting: Boolean = false,
)

data class BlocklistsUiState(
    val loading: Boolean = true,
    val lists: List<BlocklistEntity> = emptyList(),
    val update: BlocklistUpdateState = BlocklistUpdateState.Idle,
    val engine: FilterEngineStatus = FilterEngineStatus(),
    val enabledCategories: Set<FilterCategory> = FilterCategory.entries.toSet(),
    val addDialog: AddListDialogState? = null,
    val updatingListId: String? = null,
)

object BlocklistValidation {
    /** Client-side pre-check; the repository performs the authoritative validation. */
    fun validateNewList(name: String, url: String): UiText? = when {
        name.isBlank() -> UiText.Res(R.string.blocklists_add_error_name)
        !url.trim().startsWith("https://", ignoreCase = true) -> UiText.Res(R.string.blocklists_add_error_https)
        url.trim().length <= "https://".length + 3 || !url.trim().substringAfter("https://").contains('.') ->
            UiText.Res(R.string.blocklists_add_error_url)
        else -> null
    }

    fun reportMessage(report: BlocklistUpdateReport): UiText = when {
        report.failed.isNotEmpty() -> UiText.Res(
            R.string.blocklists_update_result_failed,
            report.updated.size, report.unchanged.size, report.failed.size,
        )
        else -> UiText.Res(R.string.blocklists_update_result, report.updated.size, report.unchanged.size)
    }
}

@HiltViewModel
class BlocklistsViewModel @Inject constructor(
    private val repository: BlocklistRepository,
    filterEngine: FilterEngine,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val addDialog = MutableStateFlow<AddListDialogState?>(null)
    private val updatingListId = MutableStateFlow<String?>(null)
    private val messages = Channel<UiText>(Channel.BUFFERED)
    val messageFlow: Flow<UiText> = messages.receiveAsFlow()

    val state: StateFlow<BlocklistsUiState> = combine(
        repository.observeBlocklists().map<List<BlocklistEntity>, List<BlocklistEntity>?> { it }.onStart { emit(null) },
        combine(repository.updateState, filterEngine.status, ::Pair),
        settings.settings.map { it.enabledCategories }.onStart { emit(FilterCategory.entries.toSet()) },
        addDialog,
        updatingListId,
    ) { lists, (update, engine), categories, dialog, updatingId ->
        BlocklistsUiState(
            loading = lists == null,
            lists = lists.orEmpty().sortedWith(compareBy<BlocklistEntity> { !it.builtIn }.thenBy { it.category.ordinal }.thenBy { it.name.lowercase() }),
            update = update,
            engine = engine,
            enabledCategories = categories,
            addDialog = dialog,
            updatingListId = updatingId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlocklistsUiState())

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    fun setCategoryEnabled(category: FilterCategory, enabled: Boolean) {
        viewModelScope.launch { settings.setCategoryEnabled(category, enabled) }
    }

    fun updateAll() {
        if (state.value.update is BlocklistUpdateState.Running) return
        viewModelScope.launch {
            val report = runCatching { repository.updateAll(force = true) }
            messages.send(
                report.fold(
                    onSuccess = BlocklistValidation::reportMessage,
                    onFailure = { UiText.Res(R.string.blocklists_update_error, it.message ?: it.javaClass.simpleName) },
                ),
            )
        }
    }

    fun updateList(list: BlocklistEntity) {
        if (updatingListId.value != null) return
        updatingListId.value = list.id
        viewModelScope.launch {
            val result = repository.updateList(list.id)
            updatingListId.value = null
            messages.send(
                result.fold(
                    onSuccess = { UiText.Res(R.string.blocklists_update_single_ok, list.name, it) },
                    onFailure = { UiText.Res(R.string.blocklists_update_single_failed, list.name, it.message ?: it.javaClass.simpleName) },
                ),
            )
        }
    }

    fun openAddDialog() { addDialog.value = AddListDialogState() }
    fun dismissAddDialog() { addDialog.value = null }
    fun editAddDialog(transform: (AddListDialogState) -> AddListDialogState) {
        addDialog.value = addDialog.value?.let { transform(it).copy(error = null) }
    }

    fun submitAddDialog() {
        val d = addDialog.value ?: return
        BlocklistValidation.validateNewList(d.name, d.url)?.let { err ->
            addDialog.value = d.copy(error = err)
            return
        }
        addDialog.value = d.copy(submitting = true, error = null)
        viewModelScope.launch {
            repository.addCustomList(d.name.trim(), d.url.trim(), d.category).fold(
                onSuccess = {
                    addDialog.value = null
                    messages.send(UiText.Res(R.string.blocklists_add_ok, it.name, it.ruleCount))
                },
                onFailure = {
                    addDialog.value = d.copy(submitting = false, error = UiText.Plain(it.message ?: it.javaClass.simpleName))
                },
            )
        }
    }

    fun removeCustomList(list: BlocklistEntity) {
        if (list.builtIn) return
        viewModelScope.launch {
            repository.removeCustomList(list.id)
            messages.send(UiText.Res(R.string.blocklists_removed, list.name))
        }
    }
}

data class CustomRulesUiState(
    val loading: Boolean = true,
    val tab: RuleType = RuleType.BLOCK,
    val rules: List<CustomRuleEntity> = emptyList(),
    val blockCount: Int = 0,
    val allowCount: Int = 0,
    val input: String = "",
    val error: UiText? = null,
    val submitting: Boolean = false,
)

@HiltViewModel
class CustomRulesViewModel @Inject constructor(
    private val repository: BlocklistRepository,
) : ViewModel() {
    private val tab = MutableStateFlow(RuleType.BLOCK)
    private val form = MutableStateFlow(Triple("", null as UiText?, false))
    private val messages = Channel<UiText>(Channel.BUFFERED)
    val messageFlow: Flow<UiText> = messages.receiveAsFlow()

    val state: StateFlow<CustomRulesUiState> = combine(
        repository.observeCustomRules().map<List<CustomRuleEntity>, List<CustomRuleEntity>?> { it }.onStart { emit(null) },
        tab,
        form,
    ) { rules, t, (input, error, submitting) ->
        val all = rules.orEmpty()
        CustomRulesUiState(
            loading = rules == null,
            tab = t,
            rules = all.filter { it.type == t }.sortedBy { it.domain },
            blockCount = all.count { it.type == RuleType.BLOCK },
            allowCount = all.count { it.type == RuleType.ALLOW },
            input = input,
            error = error,
            submitting = submitting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CustomRulesUiState())

    fun setTab(type: RuleType) { tab.value = type; form.value = Triple(form.value.first, null, false) }
    fun setInput(text: String) { form.value = Triple(text, null, false) }

    fun add() {
        val input = form.value.first.trim()
        if (input.isEmpty()) {
            form.value = Triple(input, UiText.Res(R.string.rules_error_empty), false)
            return
        }
        form.value = Triple(input, null, true)
        val type = tab.value
        viewModelScope.launch {
            repository.addCustomRule(input, type).fold(
                onSuccess = {
                    form.value = Triple("", null, false)
                    messages.send(UiText.Res(if (type == RuleType.BLOCK) R.string.rules_added_block else R.string.rules_added_allow, it.domain))
                },
                onFailure = { form.value = Triple(input, UiText.Plain(it.message ?: it.javaClass.simpleName), false) },
            )
        }
    }

    fun remove(rule: CustomRuleEntity) {
        viewModelScope.launch {
            repository.removeCustomRule(rule.id)
            messages.send(UiText.Res(R.string.rules_removed, rule.domain))
        }
    }
}
