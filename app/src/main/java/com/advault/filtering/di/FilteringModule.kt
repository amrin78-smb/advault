package com.advault.filtering.di

import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.core.common.RuleType
import com.advault.core.database.BlocklistEntity
import com.advault.core.database.CustomRuleEntity
import com.advault.data.models.BlocklistUpdateReport
import com.advault.data.models.BlocklistUpdateState
import com.advault.data.models.FilterEngineStatus
import com.advault.data.models.FilterVerdict
import com.advault.data.repository.BlocklistRepository
import com.advault.data.repository.FilterEngine
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

// STUB — replaced by the filtering/ implementation.
@Singleton
class StubFilterEngine @Inject constructor() : FilterEngine {
    override val status: StateFlow<FilterEngineStatus> = MutableStateFlow(FilterEngineStatus())
    override fun evaluate(domain: String, mode: FilterMode) = FilterVerdict(false, DecisionReason.NO_MATCH)
    override suspend fun reload() {}
}

// STUB
@Singleton
class StubBlocklistRepository @Inject constructor() : BlocklistRepository {
    override fun observeBlocklists(): Flow<List<BlocklistEntity>> = flowOf(emptyList())
    override val updateState: StateFlow<BlocklistUpdateState> = MutableStateFlow(BlocklistUpdateState.Idle)
    override suspend fun setEnabled(id: String, enabled: Boolean) {}
    override suspend fun addCustomList(name: String, url: String, category: FilterCategory) = Result.failure<BlocklistEntity>(NotImplementedError())
    override suspend fun removeCustomList(id: String) {}
    override suspend fun updateAll(force: Boolean) = BlocklistUpdateReport(0, emptyList(), emptyList(), emptyMap())
    override suspend fun updateList(id: String) = Result.failure<Int>(NotImplementedError())
    override fun observeCustomRules(): Flow<List<CustomRuleEntity>> = flowOf(emptyList())
    override suspend fun addCustomRule(input: String, type: RuleType) = Result.failure<CustomRuleEntity>(NotImplementedError())
    override suspend fun removeCustomRule(id: Long) {}
    override suspend fun applyUpdateSchedule() {}
    override suspend fun ensureInitialized() {}
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FilteringModule {
    @Binds abstract fun engine(impl: StubFilterEngine): FilterEngine
    @Binds abstract fun blocklists(impl: StubBlocklistRepository): BlocklistRepository
}
