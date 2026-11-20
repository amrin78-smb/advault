package com.advault.vpn.di

import com.advault.data.repository.ProtectionController
import com.advault.data.repository.StatsRepository
import com.advault.vpn.ProtectionControllerImpl
import com.advault.vpn.stats.Clock
import com.advault.vpn.stats.StatsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnModule {
    @Binds abstract fun protection(impl: ProtectionControllerImpl): ProtectionController
    @Binds abstract fun stats(impl: StatsRepositoryImpl): StatsRepository

    companion object {
        @Provides fun clock(): Clock = Clock.SYSTEM
    }
}
