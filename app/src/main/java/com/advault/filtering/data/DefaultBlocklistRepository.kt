package com.advault.filtering.data

import com.advault.core.common.ApplicationScope
import com.advault.core.common.FilterCategory
import com.advault.core.common.IoDispatcher
import com.advault.core.common.RuleType
import com.advault.core.database.BlocklistDao
import com.advault.core.database.BlocklistEntity
import com.advault.core.database.CustomRuleDao
import com.advault.core.database.CustomRuleEntity
import com.advault.data.models.BlocklistUpdateReport
import com.advault.data.models.BlocklistUpdateState
import com.advault.data.repository.BlocklistRepository
import com.advault.data.repository.ProviderRepository
import com.advault.filtering.parser.BlocklistParser
import com.advault.filtering.parser.CustomRuleValidator
import com.advault.filtering.parser.ParseLimits
import com.advault.filtering.parser.ParseStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.Writer
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultBlocklistRepository @Inject constructor(
    private val blocklistDao: BlocklistDao,
    private val customRuleDao: CustomRuleDao,
    private val store: BlocklistFileStore,
    private val assets: BlocklistAssets,
    private val downloader: BlocklistDownloader,
    private val settings: FilteringSettings,
    private val scheduler: BlocklistUpdateScheduler,
    private val providers: ProviderRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BlocklistRepository {

    private val limits = ParseLimits()
    private val updateMutex = Mutex()
    private val initMutex = Mutex()
    private val scheduleWatcherStarted = AtomicBoolean(false)
    private val scheduleLock = Any()
    @Volatile private var lastAppliedSchedule: UpdateSchedule? = null
    private val providerWatcherStarted = AtomicBoolean(false)
    private val _updateState = MutableStateFlow<BlocklistUpdateState>(BlocklistUpdateState.Idle)

    override val updateState: StateFlow<BlocklistUpdateState> = _updateState.asStateFlow()

    override fun observeBlocklists(): Flow<List<BlocklistEntity>> = blocklistDao.observeAll()

    override fun observeCustomRules(): Flow<List<CustomRuleEntity>> = customRuleDao.observeAll()

    // ------------------------------------------------------------------ initialisation

    override suspend fun ensureInitialized() = withContext(io) {
        initMutex.withLock {
            store.cleanupTemp()
            val existing = blocklistDao.getAll().associateBy { it.id }
            for (def in BuiltInLists.all) {
                val e = existing[def.id]
                val needsInstall = e == null || !store.exists(def.id) || e.lastUpdated < BuiltInLists.SNAPSHOT_EPOCH_MS
                if (needsInstall) {
                    installBundled(def, e)
                } else if (e != null && (e.name != def.name || e.url != def.url || e.attribution != def.attribution ||
                        e.category != def.category || e.format != def.format || !e.builtIn)
                ) {
                    blocklistDao.upsert(e.copy(name = def.name, url = def.url, attribution = def.attribution,
                        category = def.category, format = def.format, builtIn = true))
                }
            }
            // Built-in lists that are no longer shipped: disable rather than silently keep using them.
            val known = BuiltInLists.all.map { it.id }.toSet() + BuiltInLists.PROVIDER_SOURCE_ID
            existing.values.filter { it.builtIn && it.id !in known && it.enabled }.forEach {
                blocklistDao.upsert(it.copy(enabled = false, lastError = "No longer bundled with AdVault"))
            }
            if (blocklistDao.get(BuiltInLists.PROVIDER_SOURCE_ID) == null) {
                val count = providers.providers.value.sumOf { it.domains.size }
                blocklistDao.upsert(BuiltInLists.providerEntity(count, System.currentTimeMillis()))
            }
        }
        startProviderWatcher()
    }

    private suspend fun installBundled(def: BuiltInList, existing: BlocklistEntity?) {
        val base = def.toEntity(enabled = existing?.enabled ?: def.enabledByDefault)
        try {
            val stats = assets.open(def.asset).use { compileTo(def.id, it) }
            blocklistDao.upsert(
                base.copy(
                    ruleCount = stats.validRules, invalidRuleCount = stats.invalidRules,
                    duplicateCount = stats.duplicateRules, lastUpdated = BuiltInLists.SNAPSHOT_EPOCH_MS,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            blocklistDao.upsert((existing ?: base).copy(lastError = "Bundled list could not be installed: ${e.message}"))
        }
    }

    private fun startProviderWatcher() {
        if (!providerWatcherStarted.compareAndSet(false, true)) return
        scope.launch(io) {
            providers.providers.collect { list ->
                val count = list.sumOf { it.domains.size }
                val e = blocklistDao.get(BuiltInLists.PROVIDER_SOURCE_ID)
                if (e == null) {
                    blocklistDao.upsert(BuiltInLists.providerEntity(count, System.currentTimeMillis()))
                } else if (e.ruleCount != count) {
                    blocklistDao.upsert(e.copy(ruleCount = count, lastUpdated = System.currentTimeMillis()))
                }
            }
        }
    }

    // ------------------------------------------------------------------ list management

    override suspend fun setEnabled(id: String, enabled: Boolean) = withContext(io) {
        blocklistDao.setEnabled(id, enabled)
        val e = blocklistDao.get(id)
        if (enabled && e != null && e.url != null && e.id != BuiltInLists.PROVIDER_SOURCE_ID && !store.exists(id)) {
            scope.launch { updateList(id) }
        }
        Unit
    }

    override suspend fun addCustomList(name: String, url: String, category: FilterCategory): Result<BlocklistEntity> =
        withContext(io) {
            val cleanName = name.trim()
            val cleanUrl = url.trim()
            if (cleanName.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Enter a name for the list"))
            if (cleanName.length > MAX_NAME) return@withContext Result.failure(IllegalArgumentException("Name is too long"))
            UrlRules.httpsProblem(cleanUrl)?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
            val all = blocklistDao.getAll()
            if (all.any { it.url.equals(cleanUrl, ignoreCase = true) }) {
                return@withContext Result.failure(IllegalArgumentException("This list has already been added"))
            }
            if (all.count { !it.builtIn } >= MAX_CUSTOM_LISTS) {
                return@withContext Result.failure(IllegalStateException("At most $MAX_CUSTOM_LISTS custom lists are supported"))
            }
            val id = "user-" + UUID.randomUUID().toString().replace("-", "").take(12)
            val host = runCatching { URI(cleanUrl).host }.getOrNull() ?: cleanUrl
            try {
                val result = updateMutex.withLock {
                    downloader.fetch(cleanUrl, null, null) { compileTo(id, it) }
                }
                val fetched = result as? FetchResult.Fetched ?: throw java.io.IOException("Server returned no content")
                val stats = fetched.value
                val entity = BlocklistEntity(
                    id = id, name = cleanName, url = cleanUrl, category = category, format = stats.detectedFormat,
                    enabled = true, builtIn = false, attribution = "User-added list from $host",
                    ruleCount = stats.validRules, invalidRuleCount = stats.invalidRules, duplicateCount = stats.duplicateRules,
                    lastUpdated = System.currentTimeMillis(), etag = fetched.etag, lastModifiedHeader = fetched.lastModified,
                )
                blocklistDao.upsert(entity)
                Result.success(entity)
            } catch (e: CancellationException) {
                store.delete(id)
                throw e
            } catch (e: Exception) {
                store.delete(id)
                Result.failure(java.io.IOException("Could not add list: ${describe(e)}", e))
            }
        }

    override suspend fun removeCustomList(id: String) = withContext(io) {
        val e = blocklistDao.get(id) ?: return@withContext
        if (e.builtIn) return@withContext
        blocklistDao.deleteCustom(id)
        store.delete(id)
    }

    // ------------------------------------------------------------------ updates

    override suspend fun updateAll(force: Boolean): BlocklistUpdateReport = withContext(io) {
        updateMutex.withLock {
            val targets = blocklistDao.getAll().filter { it.enabled && it.url != null }
            val updated = ArrayList<String>()
            val unchanged = ArrayList<String>()
            val failed = LinkedHashMap<String, String>()
            targets.forEachIndexed { i, e ->
                _updateState.value = BlocklistUpdateState.Running(e.name, i, targets.size)
                when (val o = updateLocked(e.id, force)) {
                    is Outcome.Updated -> updated += e.id
                    Outcome.Unchanged -> unchanged += e.id
                    is Outcome.Failed -> failed[e.id] = o.message
                }
            }
            val report = BlocklistUpdateReport(System.currentTimeMillis(), updated, unchanged, failed)
            _updateState.value = BlocklistUpdateState.Finished(report)
            report
        }
    }

    override suspend fun updateList(id: String): Result<Int> = withContext(io) {
        updateMutex.withLock {
            val name = blocklistDao.get(id)?.name ?: id
            _updateState.value = BlocklistUpdateState.Running(name, 0, 1)
            val outcome = updateLocked(id, force = false)
            val report = when (outcome) {
                is Outcome.Updated -> BlocklistUpdateReport(System.currentTimeMillis(), listOf(id), emptyList(), emptyMap())
                Outcome.Unchanged -> BlocklistUpdateReport(System.currentTimeMillis(), emptyList(), listOf(id), emptyMap())
                is Outcome.Failed -> BlocklistUpdateReport(System.currentTimeMillis(), emptyList(), emptyList(), mapOf(id to outcome.message))
            }
            _updateState.value = BlocklistUpdateState.Finished(report)
            when (outcome) {
                is Outcome.Updated -> Result.success(outcome.rules)
                Outcome.Unchanged -> Result.success(blocklistDao.get(id)?.ruleCount ?: 0)
                is Outcome.Failed -> Result.failure(java.io.IOException(outcome.message))
            }
        }
    }

    private sealed interface Outcome {
        data class Updated(val rules: Int) : Outcome
        data object Unchanged : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Must hold [updateMutex]. Never replaces the existing file unless the new content parsed successfully. */
    private suspend fun updateLocked(id: String, force: Boolean): Outcome {
        val e = blocklistDao.get(id) ?: return Outcome.Failed("List not found")
        val url = e.url ?: return Outcome.Failed("This list has no update URL")
        UrlRules.httpsProblem(url)?.let { return fail(id, it) }
        val conditional = !force && store.exists(id)
        return try {
            when (val r = downloader.fetch(url, e.etag.takeIf { conditional }, e.lastModifiedHeader.takeIf { conditional }) { compileTo(id, it) }) {
                FetchResult.NotModified -> {
                    blocklistDao.get(id)?.let { blocklistDao.upsert(it.copy(lastUpdated = System.currentTimeMillis(), lastError = null)) }
                    Outcome.Unchanged
                }
                is FetchResult.Fetched -> {
                    val s = r.value
                    blocklistDao.get(id)?.let {
                        blocklistDao.upsert(
                            it.copy(
                                ruleCount = s.validRules, invalidRuleCount = s.invalidRules, duplicateCount = s.duplicateRules,
                                lastUpdated = System.currentTimeMillis(), lastError = null,
                                etag = r.etag, lastModifiedHeader = r.lastModified,
                                format = if (it.builtIn) it.format else s.detectedFormat,
                            ),
                        )
                    }
                    Outcome.Updated(s.validRules)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            fail(id, describe(ex))
        }
    }

    private suspend fun fail(id: String, message: String): Outcome.Failed {
        blocklistDao.get(id)?.let { blocklistDao.upsert(it.copy(lastError = message)) }
        return Outcome.Failed(message)
    }

    /** Parses [input] and atomically replaces the compiled file for [id]. */
    private fun compileTo(id: String, input: InputStream): ParseStats =
        store.writeAtomically(id) { w -> BlocklistParser.parse(input, limits) { d, sub -> writeRule(w, d, sub) } }

    // ------------------------------------------------------------------ custom rules

    override suspend fun addCustomRule(input: String, type: RuleType): Result<CustomRuleEntity> = withContext(io) {
        when (val v = CustomRuleValidator.validate(input)) {
            is CustomRuleValidator.Outcome.Invalid -> Result.failure(IllegalArgumentException(v.reason))
            is CustomRuleValidator.Outcome.Valid -> {
                val entity = CustomRuleEntity(domain = v.rule, type = type, createdAt = System.currentTimeMillis())
                val rowId = customRuleDao.insert(entity)
                if (rowId == -1L) Result.failure(IllegalArgumentException("This rule already exists"))
                else Result.success(entity.copy(id = rowId))
            }
        }
    }

    override suspend fun removeCustomRule(id: Long) = withContext(io) { customRuleDao.delete(id) }

    // ------------------------------------------------------------------ scheduling

    override suspend fun applyUpdateSchedule() {
        val schedule = settings.updateSchedule.first()
        synchronized(scheduleLock) {
            lastAppliedSchedule = schedule
            scheduler.apply(schedule)
        }
        if (scheduleWatcherStarted.compareAndSet(false, true)) {
            // Re-apply whenever the user changes frequency / Wi-Fi-only in settings.
            scope.launch {
                settings.updateSchedule.collect {
                    synchronized(scheduleLock) {
                        if (it != lastAppliedSchedule) { lastAppliedSchedule = it; scheduler.apply(it) }
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_CUSTOM_LISTS = 40
        private const val MAX_NAME = 80

        internal fun writeRule(w: Writer, domain: String, subdomainsOnly: Boolean) {
            if (subdomainsOnly) w.write("*.")
            w.write(domain)
            w.write('\n'.code)
        }

        private fun describe(e: Throwable): String = when (e) {
            is java.net.UnknownHostException -> "Host not found (${e.message})"
            is java.net.SocketTimeoutException -> "Connection timed out"
            is javax.net.ssl.SSLException -> "Secure connection failed: ${e.message}"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
