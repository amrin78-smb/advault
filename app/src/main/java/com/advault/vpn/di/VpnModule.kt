package com.advault.vpn.di

import android.content.Intent
import com.advault.data.models.DashboardStats
import com.advault.data.models.ProtectionState
import com.advault.data.models.VpnDiagnostics
import com.advault.data.repository.ProtectionController
import com.advault.data.repository.StatsRepository
import com.advault.core.database.ActivityLogEntity
import com.advault.data.models.DailyPoint
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

// STUB — replaced by the vpn/ implementation.
@Singleton
class StubProtectionController @Inject constructor() : ProtectionController {
    override val state: StateFlow<ProtectionState> = MutableStateFlow(ProtectionState.Stopped)
    override val diagnostics: StateFlow<VpnDiagnostics> = MutableStateFlow(VpnDiagnostics())
    override fun prepareIntent(): Intent? = null
    override fun start() {}
    override fun stop() {}
    override fun reload() {}
    override fun refreshDiagnostics() {}
}

// STUB
@Singleton
class StubStatsRepository @Inject constructor() : StatsRepository {
    override fun observeDashboard(): Flow<DashboardStats> = flowOf(DashboardStats())
    override fun observeDaily(days: Int): Flow<List<DailyPoint>> = flowOf(emptyList())
    override fun observeActivity(packageName: String?, providerId: String?, blockedOnly: Boolean, limit: Int): Flow<List<ActivityLogEntity>> = flowOf(emptyList())
    override fun observeActivityPackages(): Flow<List<String>> = flowOf(emptyList())
    override fun observeActivityProviders(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun clearActivity() {}
    override suspend fun clearStatistics() {}
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnModule {
    @Binds abstract fun protection(impl: StubProtectionController): ProtectionController
    @Binds abstract fun stats(impl: StubStatsRepository): StatsRepository
}
