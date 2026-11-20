package com.advault.intelligence.di

import com.advault.core.common.AppProtection
import com.advault.core.common.FilterMode
import com.advault.data.models.AdProvider
import com.advault.data.models.GameAdBehaviour
import com.advault.data.models.GameRewardProfile
import com.advault.data.models.InstalledApp
import com.advault.data.models.ProviderDatabaseInfo
import com.advault.data.models.ProviderMatch
import com.advault.data.repository.AppRepository
import com.advault.data.repository.ProviderRepository
import com.advault.data.repository.RewardAssistantRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

// STUB — replaced by the intelligence/ implementation.
@Singleton
class StubAppRepository @Inject constructor() : AppRepository {
    override fun observeApps(): Flow<List<InstalledApp>> = flowOf(emptyList())
    override suspend fun setProtection(packageName: String, protection: AppProtection) {}
    override suspend fun excludedPackages(): Set<String> = emptySet()
    override fun modeForUid(uid: Int): FilterMode? = FilterMode.AGGRESSIVE
    override fun packageForUid(uid: Int): String? = null
    override suspend fun refresh() {}
}

// STUB
@Singleton
class StubProviderRepository @Inject constructor() : ProviderRepository {
    override val providers: StateFlow<List<AdProvider>> = MutableStateFlow(emptyList())
    override val info: StateFlow<ProviderDatabaseInfo?> = MutableStateFlow(null)
    override fun match(domain: String): ProviderMatch? = null
    override suspend fun load() {}
    override suspend fun update() = Result.failure<ProviderDatabaseInfo>(NotImplementedError())
}

// STUB
@Singleton
class StubRewardAssistantRepository @Inject constructor() : RewardAssistantRepository {
    override fun observeGames(): Flow<List<GameRewardProfile>> = flowOf(emptyList())
    override suspend fun setBehaviour(packageName: String, behaviour: GameAdBehaviour) {}
}

@Module
@InstallIn(SingletonComponent::class)
abstract class IntelligenceModule {
    @Binds abstract fun apps(impl: StubAppRepository): AppRepository
    @Binds abstract fun providers(impl: StubProviderRepository): ProviderRepository
    @Binds abstract fun reward(impl: StubRewardAssistantRepository): RewardAssistantRepository
}
