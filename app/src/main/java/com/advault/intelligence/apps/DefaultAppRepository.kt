package com.advault.intelligence.apps

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.advault.core.common.AppProtection
import com.advault.core.common.ApplicationScope
import com.advault.core.common.FilterMode
import com.advault.core.common.IoDispatcher
import com.advault.core.database.AppPolicyDao
import com.advault.core.database.AppPolicyEntity
import com.advault.core.database.DailyStatsDao
import com.advault.core.preferences.SettingsRepository
import com.advault.data.models.GameAdBehaviour
import com.advault.data.models.InstalledApp
import com.advault.data.repository.AppRepository
import com.advault.data.repository.ProtectionController
import com.advault.data.repository.ProviderRepository
import com.advault.intelligence.reward.BehaviourStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Internal, scanned view of one listed app (before policy/stats are joined). */
data class AppRecord(
    val identity: AppIdentity,
    val label: String,
    val game: GameDetection,
    val sdkProviderIds: List<String>,
    val icon: Drawable?,
    val lastUpdateTime: Long,
)

/**
 * Installed applications, per-app policy and the uid → filter-mode table used by the DNS hot path.
 *
 * Loading is staged off the main thread: (1) uid table from getInstalledApplications, (2) labels and
 * cached SDK scans, (3) uncached manifest scans, (4) icons. Each stage re-emits [records].
 */
@OptIn(FlowPreview::class)
@Singleton
class DefaultAppRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
    private val policyDao: AppPolicyDao,
    private val dailyStatsDao: DailyStatsDao,
    private val settings: SettingsRepository,
    private val providerRepository: ProviderRepository,
    private val behaviourStore: BehaviourStore,
    // Lazy: the VPN controller itself depends on AppRepository.
    private val protectionController: dagger.Lazy<ProtectionController>,
) : AppRepository {

    private val pm: PackageManager = context.packageManager
    private val ownPackage: String = context.packageName
    private val scanner = PackageScanner(pm)
    private val cache = ManifestScanCache(File(File(context.filesDir, "intelligence"), "sdk_scan_cache.json"))

    private val identities = MutableStateFlow<List<AppIdentity>>(emptyList())
    private val _records = MutableStateFlow<List<AppRecord>?>(null)
    /** Listed apps with detection results; null until the first scan completes. */
    val records: StateFlow<List<AppRecord>?> = _records.asStateFlow()

    @Volatile private var snapshot: ProtectionSnapshot = ProtectionSnapshot.EMPTY
    @Volatile private var lastServedExclusions: Set<String>? = null
    @Volatile private var scannedDataVersion: String? = null
    private val refreshMutex = Mutex()
    private val iconCache = HashMap<String, Pair<Long, Drawable?>>()
    private val packageEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            combine(
                identities,
                policyDao.observeAll(),
                behaviourStore.behaviours,
                settings.settings.map { it.defaultMode }.distinctUntilChanged(),
            ) { apps, policies, behaviours, mode ->
                ProtectionResolver.resolve(apps, policies.associate { it.packageName to it.protection }, behaviours, mode, ownPackage)
            }.flowOn(io).collect { snapshot = it }
        }
        scope.launch { runCatching { refresh() }.onFailure { Log.e(TAG, "Initial app scan failed", it) } }
        // Rescan when a provider database with different signatures becomes active.
        scope.launch {
            providerRepository.info.filterNotNull().map { it.dataVersion }.distinctUntilChanged().collect { v ->
                if (scannedDataVersion != null && scannedDataVersion != v) runCatching { refresh() }
            }
        }
        scope.launch {
            packageEvents.debounce(1_500).collect {
                runCatching {
                    refresh()
                    reloadIfExclusionsChanged()
                }.onFailure { Log.w(TAG, "Refresh after package change failed", it) }
            }
        }
        registerPackageReceiver()
    }

    // ------------------------------------------------------------------ hot path

    override fun modeForUid(uid: Int): FilterMode? = snapshot.modeForUid(uid)

    override fun packageForUid(uid: Int): String? = snapshot.packageForUid(uid)

    // ------------------------------------------------------------------ queries

    override fun observeApps(): Flow<List<InstalledApp>> = combine(
        records.filterNotNull(),
        policyDao.observeAll(),
        behaviourStore.behaviours,
        dailyStatsDao.observePerPackage(0),
    ) { recs, policies, behaviours, stats ->
        val policyMap = policies.associate { it.packageName to it.protection }
        val statMap = stats.associateBy { it.packageName }
        recs.map { r ->
            val pkg = r.identity.packageName
            val s = statMap[pkg]
            InstalledApp(
                packageName = pkg,
                label = r.label,
                uid = r.identity.uid,
                // Preinstalled apps with a launcher icon (Chrome, YouTube, Gmail...) are user-facing;
                // "system" in the UI means background components only.
                isSystem = r.identity.isSystem && !r.identity.launchable,
                isGame = r.game.isGame,
                icon = r.icon,
                protection = ProtectionResolver.effectiveProtection(policyMap[pkg], behaviours[pkg]),
                detectedSdkProviderIds = r.sdkProviderIds,
                totalQueries = s?.totalQueries ?: 0,
                blockedQueries = s?.blockedQueries ?: 0,
                dnsBytes = s?.dnsBytes ?: 0,
            )
        }
    }.flowOn(io)

    override suspend fun excludedPackages(): Set<String> = withContext(io) {
        val set = computeExclusions()
        lastServedExclusions = set
        set
    }

    private suspend fun computeExclusions(): Set<String> = ProtectionResolver.excludedPackages(
        policies = policyDao.getAll().associate { it.packageName to it.protection },
        behaviours = behaviourStore.current(),
        ownPackage = ownPackage,
        installed = ::isInstalled,
    )

    // ------------------------------------------------------------------ mutations

    /**
     * Persists [protection]. An explicit protection choice also clears any Reward Assistant behaviour for
     * the app, so the user's latest decision is what applies. Reloads the VPN if the exclusion set changed.
     */
    override suspend fun setProtection(packageName: String, protection: AppProtection) = withContext(io) {
        val before = computeExclusions()
        if (behaviourStore.current()[packageName] != null) behaviourStore.set(packageName, GameAdBehaviour.DEFAULT)
        if (protection == AppProtection.DEFAULT) policyDao.delete(packageName)
        else policyDao.upsert(AppPolicyEntity(packageName, protection, System.currentTimeMillis()))
        recomputeSnapshot()
        if (computeExclusions() != before) reloadVpn()
    }

    /** Used by the Reward Assistant. Applies [behaviour] and reloads the VPN if exclusions changed. */
    suspend fun applyBehaviour(packageName: String, behaviour: GameAdBehaviour) = withContext(io) {
        val before = computeExclusions()
        behaviourStore.set(packageName, behaviour)
        recomputeSnapshot()
        if (computeExclusions() != before) reloadVpn()
    }

    override suspend fun refresh() = withContext(io) {
        refreshMutex.withLock { doRefresh() }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun recomputeSnapshot() {
        snapshot = ProtectionResolver.resolve(
            identities.value,
            policyDao.getAll().associate { it.packageName to it.protection },
            behaviourStore.current(),
            settings.current().defaultMode,
            ownPackage,
        )
    }

    private fun reloadVpn() {
        runCatching { protectionController.get().reload() }.onFailure { Log.w(TAG, "VPN reload failed", it) }
    }

    private suspend fun reloadIfExclusionsChanged() {
        val served = lastServedExclusions ?: return
        if (computeExclusions() != served) reloadVpn()
    }

    private fun isInstalled(pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private suspend fun doRefresh() {
        // Stage 1: uid table.
        val installed: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledApplications(0)
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable: Set<String> = (if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(launcherIntent, 0)
        }).mapNotNull { it.activityInfo?.packageName }.toSet()

        val ids = installed.map {
            AppIdentity(it.packageName, it.uid, isSystem(it), it.packageName in launchable)
        }
        identities.value = ids
        recomputeSnapshot()

        // Stage 2: listed apps = launchable apps + any app that can use the network (what the VPN routes).
        val listed = installed.filter { ai ->
            ai.packageName != ownPackage && (ai.packageName in launchable ||
                pm.checkPermission(Manifest.permission.INTERNET, ai.packageName) == PackageManager.PERMISSION_GRANTED)
        }
        providerRepository.load()
        val providers = providerRepository.providers.value
        val dataVersion = providerRepository.info.value?.dataVersion ?: "none"
        cache.retain(installed.map { it.packageName }.toSet())

        val versions = HashMap<String, Pair<Long, Long>>()
        fun versionOf(ai: ApplicationInfo): Pair<Long, Long> = versions.getOrPut(ai.packageName) {
            runCatching {
                val pi = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(ai.packageName, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(ai.packageName, 0)
                pi.longVersionCode to pi.lastUpdateTime
            }.getOrDefault(0L to 0L)
        }
        val idByPkg = ids.associateBy { it.packageName }
        val labels = HashMap<String, String>()
        fun record(ai: ApplicationInfo, scan: ManifestScan?, icon: Drawable?): AppRecord {
            val label = labels.getOrPut(ai.packageName) { runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName) }
            val game = GameDetector.detect(ai.category, ai.flags, scan?.engine, scan?.playGames == true)
            return AppRecord(idByPkg.getValue(ai.packageName), label, game, scan?.sdkProviderIds.orEmpty(), icon, versionOf(ai).second)
        }

        val scans = HashMap<String, ManifestScan?>()
        val toScan = ArrayList<ApplicationInfo>()
        for (ai in listed) {
            if (!shouldScan(ai)) { scans[ai.packageName] = null; continue }
            val (vc, lu) = versionOf(ai)
            val cached = cache.get(ai.packageName, vc, lu, dataVersion)
            scans[ai.packageName] = cached
            if (cached == null) toScan += ai
        }
        fun cachedIcon(ai: ApplicationInfo): Drawable? =
            iconCache[ai.packageName]?.takeIf { it.first == versionOf(ai).second }?.second
        _records.value = sortRecords(listed.map { record(it, scans[it.packageName], cachedIcon(it)) })

        // Stage 3: manifest scans for new/updated packages.
        if (toScan.isNotEmpty() && providers.isNotEmpty()) {
            for (ai in toScan) {
                val signals = scanner.readSignals(ai.packageName) ?: continue
                val (vc, lu) = versionOf(ai)
                val scan = PackageScanner.analyse(signals, providers, vc, lu, dataVersion)
                scans[ai.packageName] = scan
                cache.put(ai.packageName, scan)
            }
            cache.flush()
            _records.value = sortRecords(listed.map { record(it, scans[it.packageName], cachedIcon(it)) })
        }
        if (providers.isNotEmpty()) scannedDataVersion = dataVersion

        // Stage 4: icons (only those not cached for this app version).
        var iconsChanged = false
        for (ai in listed) {
            val lu = versionOf(ai).second
            if (iconCache[ai.packageName]?.first == lu) continue
            iconCache[ai.packageName] = lu to runCatching { pm.getApplicationIcon(ai) }.getOrNull()
            iconsChanged = true
        }
        iconCache.keys.retainAll(listed.map { it.packageName }.toSet())
        if (iconsChanged) _records.value = sortRecords(listed.map { record(it, scans[it.packageName], cachedIcon(it)) })
    }

    /** System apps that were never updated rarely bundle third-party ad SDKs; skip them to keep scans fast. */
    private fun shouldScan(ai: ApplicationInfo): Boolean =
        !isSystem(ai) || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)

    private fun isSystem(ai: ApplicationInfo) = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun sortRecords(list: List<AppRecord>) =
        list.sortedWith(compareBy<AppRecord> { it.label.lowercase() }.thenBy { it.identity.packageName })

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { packageEvents.tryEmit(Unit) }
        }
        runCatching {
            // Package broadcasts are protected system broadcasts and are delivered to non-exported receivers.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "Could not register package receiver", it) }
    }

    /** Waits for the first scan (used by the Reward Assistant). */
    suspend fun awaitRecords(): List<AppRecord> = records.filterNotNull().first()

    private companion object { const val TAG = "AppRepository" }
}
