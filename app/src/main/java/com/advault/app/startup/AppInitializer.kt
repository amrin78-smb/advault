package com.advault.app.startup

import com.advault.core.common.ApplicationScope
import com.advault.data.repository.BlocklistRepository
import com.advault.data.repository.FilterEngine
import com.advault.data.repository.ProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Application-start work. Kept off the main thread. */
@Singleton
class AppInitializer @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val blocklists: BlocklistRepository,
    private val filterEngine: FilterEngine,
    private val providers: ProviderRepository,
) {
    fun initialize() {
        scope.launch {
            providers.load()
            blocklists.ensureInitialized()
            filterEngine.reload()
            blocklists.applyUpdateSchedule()
        }
    }
}
