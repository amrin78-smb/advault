package com.advault.feature.rewards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.advault.R
import com.advault.data.models.AdProvider
import com.advault.data.models.GameAdBehaviour
import com.advault.data.models.GameRewardProfile
import com.advault.data.models.ProviderDatabaseInfo
import com.advault.data.repository.ProtectionController
import com.advault.data.repository.ProviderRepository
import com.advault.data.repository.RewardAssistantRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RewardsUiState(
    val loading: Boolean = true,
    val games: List<GameRewardProfile> = emptyList(),
    val providers: List<AdProvider> = emptyList(),
    val info: ProviderDatabaseInfo? = null,
    val updatingDatabase: Boolean = false,
)

@HiltViewModel
class RewardsViewModel @Inject constructor(
    private val rewards: RewardAssistantRepository,
    private val providerRepository: ProviderRepository,
    private val controller: ProtectionController,
) : ViewModel() {
    private val updating = MutableStateFlow(false)
    private val messages = Channel<UiText>(Channel.BUFFERED)
    val messageFlow: Flow<UiText> = messages.receiveAsFlow()

    val state: StateFlow<RewardsUiState> = combine(
        rewards.observeGames().map<List<GameRewardProfile>, List<GameRewardProfile>?> { it }.onStart { emit(null) },
        providerRepository.providers,
        providerRepository.info,
        updating,
    ) { games, providers, info, upd ->
        RewardsUiState(
            loading = games == null,
            games = games.orEmpty().sortedBy { it.label.lowercase() },
            providers = providers.sortedBy { it.name.lowercase() },
            info = info,
            updatingDatabase = upd,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RewardsUiState())

    fun setBehaviour(game: GameRewardProfile, behaviour: GameAdBehaviour) {
        if (game.behaviour == behaviour) return
        viewModelScope.launch {
            rewards.setBehaviour(game.packageName, behaviour)
            // "Allow rewarded ads" excludes the game from the VPN, so routing changes whenever it is entered or left.
            if (game.behaviour == GameAdBehaviour.ALLOW_REWARDED || behaviour == GameAdBehaviour.ALLOW_REWARDED) {
                controller.reload()
            }
            messages.send(UiText.Res(R.string.rewards_behaviour_set, game.label, behaviour.displayName))
        }
    }

    fun updateDatabase() {
        if (updating.value) return
        updating.value = true
        viewModelScope.launch {
            val result = runCatching { providerRepository.update() }.getOrElse { Result.failure(it) }
            updating.value = false
            messages.send(
                result.fold(
                    onSuccess = { UiText.Res(R.string.rewards_db_updated, it.dataVersion, it.providerCount) },
                    onFailure = { UiText.Res(R.string.rewards_db_update_failed, it.message ?: it.javaClass.simpleName) },
                ),
            )
        }
    }
}

@HiltViewModel
class ProviderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    providerRepository: ProviderRepository,
) : ViewModel() {
    val providerId: String = checkNotNull(savedStateHandle.get<String>("providerId"))
    val provider: StateFlow<AdProvider?> = providerRepository.providers
        .map { list -> list.firstOrNull { it.id == providerId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), providerRepository.providers.value.firstOrNull { it.id == providerId })
}
