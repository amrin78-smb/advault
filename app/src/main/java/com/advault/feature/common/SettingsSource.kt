package com.advault.feature.common

import com.advault.core.preferences.AppSettings
import com.advault.core.preferences.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow

/**
 * Read-only view of settings for ViewModels that only observe them. Exists so those ViewModels can be
 * unit-tested with a plain flow instead of a DataStore-backed [SettingsRepository].
 */
fun interface SettingsSource {
    fun settings(): Flow<AppSettings>
}

@Module
@InstallIn(SingletonComponent::class)
object FeatureModule {
    @Provides
    fun settingsSource(repo: SettingsRepository): SettingsSource = SettingsSource { repo.settings }
}
