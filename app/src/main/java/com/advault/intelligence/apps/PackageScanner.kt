package com.advault.intelligence.apps

import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.advault.data.models.AdProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Result of scanning one package's manifest. */
@Serializable
data class ManifestScan(
    val versionCode: Long,
    val lastUpdateTime: Long,
    val providerDataVersion: String,
    val sdkProviderIds: List<String>,
    val engine: String?,
    val playGames: Boolean,
)

/** Declared component class names and meta-data keys (application + component level). */
data class ManifestSignals(val componentNames: Set<String>, val metaDataKeys: Set<String>)

/**
 * Reads declared components / meta-data through PackageManager (no APK parsing, no code loading).
 * Must be called off the main thread.
 */
class PackageScanner(private val pm: PackageManager) {

    fun readSignals(packageName: String): ManifestSignals? {
        val all = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
        val info = getPackageInfo(packageName, all)
        if (info != null) return collect(listOf(info))
        // Very large manifests can exceed the binder transaction limit; fall back to one component type at a time.
        val parts = listOf(
            PackageManager.GET_ACTIVITIES, PackageManager.GET_SERVICES, PackageManager.GET_RECEIVERS, PackageManager.GET_PROVIDERS,
        ).mapNotNull { getPackageInfo(packageName, it or PackageManager.GET_META_DATA) ?: getPackageInfo(packageName, it) }
        return if (parts.isEmpty()) null else collect(parts)
    }

    private fun collect(infos: List<PackageInfo>): ManifestSignals {
        val names = HashSet<String>()
        val keys = HashSet<String>()
        fun meta(b: Bundle?) { b?.keySet()?.let(keys::addAll) }
        for (info in infos) {
            meta(info.applicationInfo?.metaData)
            val comps = ArrayList<ComponentInfo>()
            info.activities?.let { comps.addAll(it) }
            info.services?.let { comps.addAll(it) }
            info.receivers?.let { comps.addAll(it) }
            info.providers?.let { comps.addAll(it) }
            for (c in comps) {
                c.name?.let(names::add)
                meta(c.metaData)
            }
        }
        return ManifestSignals(names, keys)
    }

    private fun getPackageInfo(pkg: String, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(pkg, flags)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: Exception) {
        // e.g. DeadSystemException / TransactionTooLargeException wrapped in RuntimeException.
        Log.w("PackageScanner", "getPackageInfo($pkg, $flags) failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        fun analyse(signals: ManifestSignals, providers: List<AdProvider>, versionCode: Long, lastUpdate: Long, dataVersion: String) =
            ManifestScan(
                versionCode = versionCode,
                lastUpdateTime = lastUpdate,
                providerDataVersion = dataVersion,
                sdkProviderIds = SdkDetector.detect(signals.componentNames, signals.metaDataKeys, providers),
                engine = GameDetector.detectEngine(signals.componentNames),
                playGames = GameDetector.PLAY_GAMES_APP_ID in signals.metaDataKeys,
            )
    }
}

/**
 * Memory + small JSON file cache of [ManifestScan] per package. An entry is valid only for the same
 * versionCode, lastUpdateTime and provider dataVersion, so app updates and new signatures trigger a rescan.
 */
class ManifestScanCache(private val file: File) {
    private val serializer = MapSerializer(String.serializer(), ManifestScan.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val entries = HashMap<String, ManifestScan>()
    private var loaded = false
    private var dirty = false

    @Synchronized
    fun get(pkg: String, versionCode: Long, lastUpdate: Long, dataVersion: String): ManifestScan? {
        ensureLoaded()
        return entries[pkg]?.takeIf {
            it.versionCode == versionCode && it.lastUpdateTime == lastUpdate && it.providerDataVersion == dataVersion
        }
    }

    @Synchronized
    fun put(pkg: String, scan: ManifestScan) {
        ensureLoaded()
        if (entries[pkg] != scan) { entries[pkg] = scan; dirty = true }
    }

    /** Drops entries for packages no longer installed. */
    @Synchronized
    fun retain(installed: Set<String>) {
        ensureLoaded()
        if (entries.keys.retainAll(installed)) dirty = true
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, entries))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            dirty = false
        }.onFailure { Log.w("ManifestScanCache", "flush failed: ${it.message}") }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile || file.length() > 4 * 1024 * 1024) return
        runCatching { entries.putAll(json.decodeFromString(serializer, file.readText())) }
            .onFailure { file.delete() }
    }
}
