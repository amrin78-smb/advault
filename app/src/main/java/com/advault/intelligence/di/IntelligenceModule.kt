package com.advault.intelligence.di

import com.advault.data.repository.AppRepository
import com.advault.data.repository.ProviderRepository
import com.advault.data.repository.RewardAssistantRepository
import com.advault.intelligence.apps.DefaultAppRepository
import com.advault.intelligence.provider.DefaultProviderRepository
import com.advault.intelligence.provider.HttpsProviderDownloader
import com.advault.intelligence.provider.ProviderDownloader
import com.advault.intelligence.reward.BehaviourStore
import com.advault.intelligence.reward.DataStoreBehaviourStore
import com.advault.intelligence.reward.DefaultRewardAssistantRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class IntelligenceModule {
    @Binds abstract fun apps(impl: DefaultAppRepository): AppRepository
    @Binds abstract fun providers(impl: DefaultProviderRepository): ProviderRepository
    @Binds abstract fun reward(impl: DefaultRewardAssistantRepository): RewardAssistantRepository
    @Binds abstract fun behaviourStore(impl: DataStoreBehaviourStore): BehaviourStore
    @Binds abstract fun downloader(impl: HttpsProviderDownloader): ProviderDownloader
}
