package com.advault.filtering.di

import android.content.Context
import com.advault.data.repository.BlocklistRepository
import com.advault.data.repository.FilterEngine
import com.advault.filtering.data.AndroidBlocklistAssets
import com.advault.filtering.data.BlocklistAssets
import com.advault.filtering.data.BlocklistDownloader
import com.advault.filtering.data.BlocklistFileStore
import com.advault.filtering.data.BlocklistUpdateScheduler
import com.advault.filtering.data.DefaultBlocklistRepository
import com.advault.filtering.data.FilteringSettings
import com.advault.filtering.data.HttpsBlocklistDownloader
import com.advault.filtering.data.SettingsRepositoryFilteringSettings
import com.advault.filtering.engine.DefaultFilterEngine
import com.advault.filtering.work.WorkManagerUpdateScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FilteringModule {
    @Binds abstract fun engine(impl: DefaultFilterEngine): FilterEngine
    @Binds abstract fun blocklists(impl: DefaultBlocklistRepository): BlocklistRepository
    @Binds abstract fun downloader(impl: HttpsBlocklistDownloader): BlocklistDownloader
    @Binds abstract fun assets(impl: AndroidBlocklistAssets): BlocklistAssets
    @Binds abstract fun settings(impl: SettingsRepositoryFilteringSettings): FilteringSettings
    @Binds abstract fun scheduler(impl: WorkManagerUpdateScheduler): BlocklistUpdateScheduler

    companion object {
        @Provides @Singleton
        fun fileStore(@ApplicationContext context: Context): BlocklistFileStore =
            BlocklistFileStore(File(context.filesDir, "blocklists"))
    }
}
