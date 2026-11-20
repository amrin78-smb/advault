package com.advault.intelligence.provider

import android.content.Context
import android.util.Log
import com.advault.core.common.IoDispatcher
import com.advault.data.models.AdProvider
import com.advault.data.models.ProviderDatabaseInfo
import com.advault.data.models.ProviderMatch
import com.advault.data.repository.ProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider database: bundled asset, optionally superseded by a validated, strictly newer download
 * stored in filesDir/intelligence/providers.json. [match] reads a volatile immutable snapshot.
 */
@Singleton
class DefaultProviderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val downloader: ProviderDownloader,
) : ProviderRepository {

    private val _providers = MutableStateFlow<List<AdProvider>>(emptyList())
    override val providers: StateFlow<List<AdProvider>> = _providers.asStateFlow()
    private val _info = MutableStateFlow<ProviderDatabaseInfo?>(null)
    override val info: StateFlow<ProviderDatabaseInfo?> = _info.asStateFlow()

    @Volatile private var matcher: ProviderMatcher = ProviderMatcher.EMPTY
    @Volatile private var current: ProviderDatabase? = null
    private val mutex = Mutex()

    private val overrideFile: File get() = File(File(context.filesDir, DIR), FILE)

    override fun match(domain: String): ProviderMatch? = matcher.match(domain)

    /** The active database, loading it first if needed. */
    suspend fun database(): ProviderDatabase? {
        current?.let { return it }
        load()
        return current
    }

    override suspend fun load() {
        withContext(io) {
            mutex.withLock {
                if (current != null) return@withLock
                val bundled = runCatching {
                    val text = context.assets.open("$DIR/$FILE").bufferedReader().use { it.readText() }
                    ProviderDatabaseParser.parse(text, SOURCE_BUNDLED)
                }.onFailure { Log.e(TAG, "Bundled provider database invalid", it) }.getOrNull()
                val override = readOverride()
                val chosen = when {
                    bundled == null -> override
                    else -> ProviderDatabaseParser.choose(bundled, override)
                }
                if (override != null && chosen !== override) {
                    // The bundled data (e.g. after an APK update) is at least as new: drop the stale download.
                    overrideFile.delete()
                }
                chosen?.let(::apply)
            }
        }
    }

    override suspend fun update(): Result<ProviderDatabaseInfo> = withContext(io) {
        runCatching {
            load()
            val text = downloader.download(UPDATE_URL, ProviderDatabaseParser.MAX_BYTES)
            val downloaded = ProviderDatabaseParser.parse(text, UPDATE_URL)
            mutex.withLock {
                val active = current
                if (active != null && ProviderDatabaseParser.compareDataVersions(downloaded.dataVersion, active.dataVersion) <= 0) {
                    return@withLock active.info // Already up to date.
                }
                writeAtomically(overrideFile, text)
                apply(downloaded)
                downloaded.info
            }
        }.onFailure { Log.w(TAG, "Provider database update failed: ${it.message}") }
    }

    private fun readOverride(): ProviderDatabase? {
        val f = overrideFile
        if (!f.isFile) return null
        if (f.length() > ProviderDatabaseParser.MAX_BYTES) { f.delete(); return null }
        return runCatching { ProviderDatabaseParser.parse(f.readText(), UPDATE_URL) }
            .onFailure { Log.w(TAG, "Discarding invalid downloaded provider database: ${it.message}"); f.delete() }
            .getOrNull()
    }

    private fun apply(db: ProviderDatabase) {
        matcher = ProviderMatcher.build(db.providers)
        current = db
        _providers.value = db.providers
        _info.value = db.info
    }

    companion object {
        private const val TAG = "ProviderRepository"
        const val DIR = "intelligence"
        const val FILE = "providers.json"
        const val SOURCE_BUNDLED = "bundled"
        const val UPDATE_URL = "https://raw.githubusercontent.com/amrin78-smb/advault/main/app/src/main/assets/intelligence/providers.json"

        /** Writes to a temp file in the same directory and atomically renames it over [target]. */
        internal fun writeAtomically(target: File, text: String) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
