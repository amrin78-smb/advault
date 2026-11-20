package com.advault.filtering.data

import android.content.Context
import com.advault.core.common.FilterCategory
import com.advault.core.common.UpdateFrequency
import com.advault.core.preferences.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/** Read access to bundled blocklist snapshots (assets/blocklists/). */
interface BlocklistAssets {
    fun open(name: String): InputStream
}

class AndroidBlocklistAssets @Inject constructor(@ApplicationContext private val context: Context) : BlocklistAssets {
    override fun open(name: String): InputStream = context.assets.open("${BuiltInLists.ASSET_DIR}/$name")
}

/** Reads assets from a directory, e.g. app/src/main/assets/blocklists in JVM tests. */
class DirectoryBlocklistAssets(private val dir: File) : BlocklistAssets {
    override fun open(name: String): InputStream = File(dir, name).inputStream()
}

data class UpdateSchedule(val frequency: UpdateFrequency, val unmeteredOnly: Boolean)

/** The subset of settings the filtering module depends on. */
interface FilteringSettings {
    val enabledCategories: Flow<Set<FilterCategory>>
    val updateSchedule: Flow<UpdateSchedule>
}

class SettingsRepositoryFilteringSettings @Inject constructor(settings: SettingsRepository) : FilteringSettings {
    override val enabledCategories: Flow<Set<FilterCategory>> =
        settings.settings.map { it.enabledCategories }.distinctUntilChanged()
    override val updateSchedule: Flow<UpdateSchedule> =
        settings.settings.map { UpdateSchedule(it.updateFrequency, it.updateOnWifiOnly) }.distinctUntilChanged()
}

/** Schedules periodic background updates. */
interface BlocklistUpdateScheduler {
    fun apply(schedule: UpdateSchedule)
}
