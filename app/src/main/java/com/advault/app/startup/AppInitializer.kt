package com.advault.app.startup

import com.advault.core.common.ApplicationScope
import com.advault.data.repository.BlocklistRepository
import com.advault.data.repository.FilterEngine
import com.advault.data.repository.ProviderRepository
import com.advault.vpn.ProtectionWatchdog
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
    private val watchdog: ProtectionWatchdog,
) {
    fun initialize() {
        scope.launch {
            // Resume protection first if the user left it on and Android killed our process.
            watchdog.ensureRunning()
            watchdog.schedule()
        }
        scope.launch {
            providers.load()
            blocklists.ensureInitialized()
            filterEngine.reload()
            blocklists.applyUpdateSchedule()
        }
    }
}
