package com.advault.filtering.engine

import com.advault.core.common.ApplicationScope
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.core.common.IoDispatcher
import com.advault.core.database.BlocklistDao
import com.advault.core.database.BlocklistEntity
import com.advault.core.database.CustomRuleDao
import com.advault.data.models.FilterEngineStatus
import com.advault.data.models.FilterVerdict
import com.advault.data.repository.FilterEngine
import com.advault.data.repository.ProviderRepository
import com.advault.filtering.data.BlocklistAssets
import com.advault.filtering.data.BlocklistFileStore
import com.advault.filtering.data.BuiltInLists
import com.advault.filtering.data.FilteringSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [FilterEngine].
 *
 * The compiled [FilterSnapshot] lives in a volatile field and is replaced atomically after each
 * rebuild, so [evaluate] (called from the VPN reader thread for every DNS query) never blocks and
 * never sees a half-built state. Category toggles only update [enabledMask] (no rebuild needed).
 *
 * Reloads are triggered automatically (debounced) when enabled lists / their compiled files, custom
 * rules, or provider-intelligence domains change; identical inputs are not rebuilt twice.
 * Until the first load completes every query yields NO_MATCH (fail open).
 */
@Singleton
class DefaultFilterEngine @Inject constructor(
    private val blocklistDao: BlocklistDao,
    private val customRuleDao: CustomRuleDao,
    private val providers: ProviderRepository,
    private val settings: FilteringSettings,
    private val store: BlocklistFileStore,
    private val assets: BlocklistAssets,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : FilterEngine {

    @Volatile private var snapshot: FilterSnapshot = FilterSnapshot.EMPTY
    @Volatile private var enabledMask: Int = FilterSnapshot.categoryMask(FilterCategory.entries)
    @Volatile private var loadedSignature: Long = 0L

    private val reloadMutex = Mutex()
    private val _status = MutableStateFlow(FilterEngineStatus())
    override val status: StateFlow<FilterEngineStatus> = _status.asStateFlow()

    private val builtinAllowText: String by lazy {
        try {
            assets.open(BuiltInLists.BUILTIN_ALLOW_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            log.log(Level.WARNING, "builtin allow list unavailable", e)
            ""
        }
    }

    init {
        scope.launch {
            settings.enabledCategories.collect { enabledMask = FilterSnapshot.categoryMask(it) }
        }
        startAutoReload()
    }

    @OptIn(FlowPreview::class)
    private fun startAutoReload() {
        scope.launch(io) {
            combine(blocklistDao.observeAll(), customRuleDao.observeAll(), providers.providers) { _, _, _ -> Unit }
                .debounce(AUTO_RELOAD_DEBOUNCE_MS)
                .collect {
                    try {
                        reloadIfChanged()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.log(Level.WARNING, "auto reload failed", e)
                    }
                }
        }
    }

    override fun evaluate(domain: String, mode: FilterMode): FilterVerdict =
        snapshot.evaluate(domain, mode, enabledMask)

    override suspend fun reload() {
        reloadInternal(force = true)
    }

    private suspend fun reloadIfChanged() = reloadInternal(force = false)

    private suspend fun reloadInternal(force: Boolean) = withContext(io) {
        reloadMutex.withLock {
            val lists = blocklistDao.getAll().filter { it.enabled }
            val rules = customRuleDao.getAll()
            val providerList = providers.providers.value
            val providerEnabled = blocklistDao.get(BuiltInLists.PROVIDER_SOURCE_ID)?.enabled ?: true
            val signature = signature(lists, rules.hashCode(), if (providerEnabled) providerList.hashCode() else 0)
            if (!force && signature == loadedSignature && _status.value.loaded) return@withLock

            _status.update { it.copy(loading = true) }
            val started = System.nanoTime()
            try {
                val expected = lists.sumOf { it.ruleCount }.coerceAtLeast(1024)
                val b = FilterSnapshotBuilder(expected)
                for (e in lists) {
                    if (e.id == BuiltInLists.PROVIDER_SOURCE_ID || !store.exists(e.id)) continue
                    try {
                        store.open(e.id).use { b.addCompiledList(e.id, e.category, it) }
                    } catch (ex: Exception) {
                        log.log(Level.WARNING, "could not load list ${e.id}", ex)
                    }
                }
                if (providerEnabled) b.addProviders(providerList, BuiltInLists.PROVIDER_SOURCE_ID)
                b.addBuiltinAllow(builtinAllowText)
                b.addCustomRules(rules)
                val snap = b.build()
                snapshot = snap
                loadedSignature = signature
                val now = System.currentTimeMillis()
                _status.value = FilterEngineStatus(
                    loaded = true,
                    loading = false,
                    totalDomains = snap.totalDomains,
                    domainsPerCategory = snap.domainsPerCategory,
                    customBlockRules = snap.customBlock.size,
                    customAllowRules = snap.customAllow.size,
                    lastLoadedAt = now,
                    loadDurationMs = (System.nanoTime() - started) / 1_000_000,
                )
            } catch (e: Throwable) {
                // Keep serving the previous snapshot (including on OutOfMemoryError).
                _status.update { it.copy(loading = false) }
                if (e is CancellationException) throw e
                log.log(Level.SEVERE, "filter reload failed", e)
            }
        }
    }

    private fun signature(lists: List<BlocklistEntity>, rulesHash: Int, providersHash: Int): Long {
        var h = 1125899906842597L
        fun mix(v: Long) { h = 31 * h + v }
        for (e in lists.sortedBy { it.id }) {
            mix(e.id.hashCode().toLong()); mix(e.category.ordinal.toLong())
            if (e.id != BuiltInLists.PROVIDER_SOURCE_ID) {
                val f = store.file(e.id)
                mix(f.length()); mix(f.lastModified())
            }
        }
        mix(rulesHash.toLong()); mix(providersHash.toLong())
        return h
    }

    companion object {
        const val AUTO_RELOAD_DEBOUNCE_MS = 500L
        private val log = Logger.getLogger("AdVault.FilterEngine")
    }
}
