package com.advault.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import com.advault.core.common.AppProtection
import com.advault.core.common.ApplicationScope
import com.advault.core.preferences.AppSettings
import com.advault.core.preferences.SettingsRepository
import com.advault.core.preferences.UpstreamPreset
import com.advault.data.models.ProtectionState
import com.advault.data.repository.AppRepository
import com.advault.data.repository.FilterEngine
import com.advault.data.repository.ProviderRepository
import com.advault.vpn.core.DnsQueryHandler
import com.advault.vpn.core.HandlerConfig
import com.advault.vpn.core.PacketWriter
import com.advault.vpn.core.ProviderClassifier
import com.advault.vpn.core.RepositoryAppPolicy
import com.advault.vpn.core.UidResolver
import com.advault.vpn.core.dns.DnsCache
import com.advault.vpn.core.upstream.SocketProtector
import com.advault.vpn.core.upstream.UpstreamConfig
import com.advault.vpn.core.upstream.UpstreamResolver
import com.advault.vpn.core.upstream.UpstreamSpec
import com.advault.vpn.stats.StatsRecorder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.inject.Inject

/**
 * DNS-redirect local VPN (DNS66 / AdAway VPN-mode design).
 *
 * The TUN only carries traffic to two virtual DNS server addresses (one IPv4, one IPv6): those are the
 * interface's DNS servers and the ONLY routes. Every other packet keeps using the underlying network
 * directly, so TCP/UDP/IPv6 connectivity is never touched and no user traffic is proxied. Our own package
 * is excluded so upstream DNS / blocklist downloads never loop through the tunnel.
 */
@AndroidEntryPoint
class AdVaultVpnService : VpnService() {

    @Inject lateinit var controller: ProtectionControllerImpl
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var filterEngine: FilterEngine
    @Inject lateinit var appRepository: AppRepository
    @Inject lateinit var providerRepository: ProviderRepository
    @Inject lateinit var statsRecorder: StatsRecorder
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()
    private val cache = DnsCache()
    private val health = UpstreamHealthTracker()
    private lateinit var cm: ConnectivityManager

    @Volatile private var settings = AppSettings()
    @Volatile private var tun: TunInterface? = null
    @Volatile private var upstream: UpstreamResolver? = null
    @Volatile private var upstreamSpec: UpstreamSpec? = null
    @Volatile private var underlying: UnderlyingNetwork? = null
    private var upstreamKey: Any? = null
    private var handler: DnsQueryHandler? = null
    private var engineScope: CoroutineScope? = null
    private var addresses: TunAddresses? = null
    private var networkMonitor: UnderlyingNetworkMonitor? = null
    private var observerJobs: List<Job> = emptyList()
    @Volatile private var running = false
    private var tunFailures = 0
    private var sessionStart = 0L
    private var ipv6 = false
    private var excludedCount = 0
    private var protectedCount = 0
    private var lastNotificationText: String? = null

    private val protector = object : SocketProtector {
        override fun protect(socket: DatagramSocket) = this@AdVaultVpnService.protect(socket)
        override fun protect(socket: Socket) = this@AdVaultVpnService.protect(socket)
    }

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        controller.activeService = this
        VpnNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                launchLifecycle { stopVpn(ProtectionState.Stopped) }
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (running) launchLifecycle { reestablish("reload") }
                return START_STICKY
            }
            else -> {
                // ACTION_START, null intent (sticky restart after process death) or SERVICE_INTERFACE (always-on).
                val systemRestart = intent == null
                val alwaysOn = intent?.action == SERVICE_INTERFACE
                if (prepare(this) != null) {
                    controller.setState(ProtectionState.Error("VPN permission is not granted. Open AdVault and turn protection on.", recoverable = true))
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startForegroundCompat()) {
                    controller.setState(ProtectionState.Error("Android refused to start the protection service.", recoverable = true))
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (systemRestart) controller.incrementRestartCount()
                if (alwaysOn) controller.setDesired(true)
                launchLifecycle { startVpn() }
                return START_STICKY
            }
        }
    }

    // ---------------------------------------------------------------- controller API (any thread)

    internal fun requestStop() = launchLifecycle { stopVpn(ProtectionState.Stopped) }
    internal fun requestReload() = launchLifecycle { if (running) reestablish("reload") }

    override fun onRevoke() {
        // Another VPN app took over, or the user revoked permission in Settings.
        controller.setState(
            ProtectionState.Revoked(
                "AdVault's VPN permission was revoked, or another VPN app was started. Protection is off; " +
                    "turn it on again to resume (this will disconnect the other VPN)."
            )
        )
        controller.setDesired(false)
        launchLifecycle { stopVpn(null) }
    }

    override fun onDestroy() {
        controller.activeService = null
        if (running || tun != null) teardown()
        val s = controller.state.value
        if (s is ProtectionState.Running || s is ProtectionState.Starting || s is ProtectionState.WaitingForNetwork) {
            controller.setState(ProtectionState.Stopped)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle (serialized by [lifecycle])

    private fun launchLifecycle(block: suspend () -> Unit) {
        serviceScope.launch {
            lifecycle.withLock {
                try { block() } catch (e: Exception) {
                    Log.e(TAG, "Lifecycle operation failed", e)
                    controller.setState(ProtectionState.Error("Protection failed: ${e.message}", recoverable = true))
                    teardown()
                    stopSelfCompat()
                }
            }
        }
    }

    private suspend fun startVpn() {
        if (running && tun != null) return // idempotent
        controller.setState(ProtectionState.Starting)
        settings = settingsRepository.current()
        startObserversOnce()
        underlying = networkMonitor?.current()
        rebuildUpstream(force = true)
        if (!establish()) {
            stopVpn(null)
            return
        }
        running = true
        tunFailures = 0
        sessionStart = System.currentTimeMillis()
        cache.resetStats()
        publishRunningState()
        controller.refreshDiagnostics()
    }

    private suspend fun reestablish(reason: String) {
        Log.i(TAG, "Re-establishing tunnel ($reason)")
        if (!establish()) {
            stopVpn(null)
            return
        }
        cache.clear()
        publishRunningState()
    }

    /** Builds and establishes the TUN, closing any previous one first (never two tunnels at once). */
    private suspend fun establish(): Boolean {
        tun?.close()
        tun = null

        val excluded = try { appRepository.excludedPackages() } catch (e: Exception) {
            Log.w(TAG, "excludedPackages failed", e); emptySet()
        } + packageName
        // Keep the current addresses on re-establish (our own tun would otherwise look like a conflict).
        val addr = addresses ?: TunAddresses.choose()
        if (addr != addresses || handler == null) createHandler(addr)
        addresses = addr

        val builder = Builder()
            .setSession("AdVault")
            .setMtu(MTU)
            .setBlocking(true)
            .setConfigureIntent(VpnNotifications.contentIntent(this))
            .addAddress(addr.v4Local, 32)
            .addRoute(addr.v4Dns, 32)
            .addDnsServer(addr.v4Dns)
        ipv6 = try {
            builder.addAddress(V6_LOCAL, 128).addRoute(V6_DNS, 128).addDnsServer(V6_DNS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "IPv6 DNS not available", e); false
        }
        builder.setMetered(false) // inherit meteredness from the underlying network
        builder.setUnderlyingNetworks(underlying?.let { arrayOf(it.network) })
        var excludedApplied = 0
        for (pkg in excluded) {
            try {
                builder.addDisallowedApplication(pkg)
                if (pkg != packageName) excludedApplied++
            } catch (_: PackageManager.NameNotFoundException) {
                // uninstalled since the policy was saved
            }
        }
        excludedCount = excludedApplied

        val pfd = try { builder.establish() } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e); null
        }
        if (pfd == null) {
            controller.setState(
                ProtectionState.Error(
                    "Android did not allow AdVault to create its VPN. Another always-on VPN may be configured, or permission was withdrawn.",
                    recoverable = true,
                )
            )
            return false
        }
        val h = handler!!
        val t = TunInterface(pfd, MTU, onPacket = { buf, len -> h.handlePacket(buf, len) }, onFailure = ::onTunFailure)
        tun = t
        t.start()
        return true
    }

    private fun createHandler(addr: TunAddresses) {
        engineScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(64))
        engineScope = scope
        handler = DnsQueryHandler(
            virtualDnsAddresses = listOf(InetAddress.getByName(addr.v4Dns).address, InetAddress.getByName(V6_DNS).address),
            filter = filterEngine,
            policy = RepositoryAppPolicy(appRepository) { settings.defaultMode },
            classifier = ProviderClassifier { d -> providerRepository.match(d)?.provider?.id },
            uidResolver = UidResolver { proto, src, sport, dst, dport ->
                try {
                    cm.getConnectionOwnerUid(
                        proto,
                        InetSocketAddress(InetAddress.getByAddress(src), sport),
                        InetSocketAddress(InetAddress.getByAddress(dst), dport),
                    )
                } catch (_: Exception) { -1 }
            },
            recorder = statsRecorder.recorder,
            writer = PacketWriter { p -> tun?.writer?.write(p) },
            cache = cache,
            upstream = { upstream },
            ioScope = scope,
            maxUdpPayload = MTU - 48,
        ).also { it.config = handlerConfig(settings) }
    }

    private fun onTunFailure(error: Throwable?) {
        launchLifecycle {
            if (!running) return@launchLifecycle
            tunFailures++
            controller.incrementRestartCount()
            if (tunFailures > 5) {
                controller.setState(ProtectionState.Error("The VPN interface keeps failing (${error?.message}).", recoverable = true))
                stopVpn(null)
                return@launchLifecycle
            }
            delay(500L * tunFailures)
            if (running) reestablish("tun failure: ${error?.message}")
        }
    }

    /** [finalState] null keeps whatever state was already published (Error/Revoked). */
    private fun stopVpn(finalState: ProtectionState?) {
        teardown()
        if (finalState != null) controller.setState(finalState)
        else if (controller.state.value.let { it is ProtectionState.Starting || it is ProtectionState.Running || it is ProtectionState.WaitingForNetwork }) {
            controller.setState(ProtectionState.Stopped)
        }
        stopSelfCompat()
    }

    private fun teardown() {
        running = false
        tun?.close()
        tun = null
        observerJobs.forEach { it.cancel() }
        observerJobs = emptyList()
        networkMonitor?.stop()
        networkMonitor = null
        engineScope?.cancel()
        engineScope = null
        handler = null
        addresses = null
        upstream?.close()
        upstream = null
        upstreamKey = null
        cache.clear()
        appScope.launch(NonCancellable) { statsRecorder.flush() }
    }

    private fun stopSelfCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startForegroundCompat(): Boolean = try {
        val n = VpnNotifications.build(this, "Starting protection…", null)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, VpnNotifications.NOTIFICATION_ID, n, type)
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        false
    }

    // ---------------------------------------------------------------- observers

    private fun startObserversOnce() {
        if (observerJobs.isNotEmpty()) return
        val monitor = UnderlyingNetworkMonitor(cm) { net -> launchLifecycle { onUnderlyingChanged(net) } }
        networkMonitor = monitor
        monitor.start()

        observerJobs = listOf(
            serviceScope.launch {
                settingsRepository.settings.collect { s ->
                    lifecycle.withLock { applySettings(s) }
                }
            },
            serviceScope.launch {
                filterEngine.status.map { it.loaded to it.loading }.distinctUntilChanged().collect { publishEngineNotes() }
            },
            statsRecorder.launchIn(serviceScope),
            serviceScope.launch { ticker() },
        )
    }

    private fun applySettings(s: AppSettings) {
        val old = settings
        settings = s
        handler?.config = handlerConfig(s)
        if (!s.dnsCacheEnabled) cache.clear()
        if (old.defaultMode != s.defaultMode || old.enabledCategories != s.enabledCategories) cache.clear()
        if (running) rebuildUpstream(force = false)
        if (old.showStatsInNotification != s.showStatsInNotification) lastNotificationText = null
    }

    private fun handlerConfig(s: AppSettings) = HandlerConfig(
        blockResponse = s.blockResponse,
        cacheEnabled = s.dnsCacheEnabled,
        timeoutMs = s.dnsTimeoutMs.coerceIn(500, 15_000),
    )

    private fun rebuildUpstream(force: Boolean) {
        val s = settings
        val systemDns = underlying?.dnsServers.orEmpty()
        val key = listOf(s.upstreamPreset, s.customUpstreamProtocol, s.customUpstream,
            if (s.upstreamPreset == UpstreamPreset.SYSTEM) systemDns else null)
        if (!force && key == upstreamKey && upstream != null) return
        val spec = UpstreamConfig.resolve(s.upstreamPreset, s.customUpstreamProtocol, s.customUpstream, systemDns)
        val resolver = UpstreamConfig.buildResolver(spec, protector, health) { url ->
            (underlying?.network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        }
        val old = upstream
        upstream = resolver
        upstreamSpec = spec
        upstreamKey = key
        old?.close()
        health.reset()
        publishEngineNotes()
        if (running) publishRunningState()
    }

    private fun onUnderlyingChanged(net: UnderlyingNetwork?) {
        val previous = underlying
        underlying = net
        if (!running) return
        runCatching { setUnderlyingNetworks(net?.let { arrayOf(it.network) }) }
        if (previous?.network != net?.network) cache.clear() // answers may be network-specific (split DNS, CDNs)
        if (settings.upstreamPreset == UpstreamPreset.SYSTEM) rebuildUpstream(force = false)
        publishRunningState()
        controller.refreshDiagnostics()
    }

    private fun publishRunningState() {
        if (!running) return
        val now = System.currentTimeMillis()
        controller.setState(
            if (underlying == null) ProtectionState.WaitingForNetwork(now)
            else ProtectionState.Running(
                since = sessionStart,
                upstreamDescription = upstreamSpec?.description ?: "Unknown",
                protectedAppCount = protectedCount,
                excludedAppCount = excludedCount,
                ipv6 = ipv6,
            )
        )
        // Protected app count can be slow to compute (package scan); refresh it asynchronously.
        serviceScope.launch {
            val apps = withTimeoutOrNull(3_000) { runCatching { appRepository.observeApps().first() }.getOrNull() }
            if (apps != null) {
                val count = apps.count { it.protection != AppProtection.EXCLUDED }
                if (count != protectedCount) {
                    protectedCount = count
                    val s = controller.state.value
                    if (s is ProtectionState.Running) controller.setState(s.copy(protectedAppCount = count))
                }
            }
        }
        lastNotificationText = null
        updateNotification()
    }

    private fun publishEngineNotes() {
        val notes = ArrayList<String>()
        upstreamSpec?.notes?.let(notes::addAll)
        val st = filterEngine.status.value
        if (!st.loaded) notes += if (st.loading) "Blocklists are loading; queries are forwarded unfiltered until ready." else "Blocklists are not loaded yet; nothing is being blocked."
        if (upstreamSpec?.protocol != null && settings.upstreamPreset == UpstreamPreset.SYSTEM && underlying?.dnsServers.isNullOrEmpty()) {
            notes += "The current network did not provide DNS servers."
        }
        controller.updateDiagnostics(engineNotes = notes) { it }
    }

    private suspend fun ticker() {
        var tick = 0
        while (serviceScope.isActive) {
            delay(5_000)
            if (!running) continue
            val h = handler ?: continue
            controller.updateDiagnostics {
                it.copy(
                    upstreamHealthy = health.healthy,
                    lastUpstreamError = health.lastError,
                    averageUpstreamLatencyMs = health.averageLatencyMs,
                    cacheEntries = cache.size,
                    cacheHitRate = cache.hitRate,
                    queriesThisSession = h.counters.queries.get(),
                    blockedThisSession = h.counters.blocked.get(),
                )
            }
            if (++tick % 2 == 0) updateNotification() // at most every 10 s
        }
    }

    private fun updateNotification() {
        if (!running) return
        val h = handler
        val waiting = underlying == null
        val title = if (waiting) "Waiting for network" else "Protection active"
        val text = when {
            waiting -> "Filtering resumes automatically when a network is available"
            settings.showStatsInNotification && h != null ->
                "${h.counters.blocked.get()} blocked · ${h.counters.queries.get()} DNS queries this session"
            else -> "Filtering DNS on this device"
        }
        val key = "$title|$text"
        if (key == lastNotificationText) return
        lastNotificationText = key
        VpnNotifications.update(this, VpnNotifications.build(this, title, text))
    }

    companion object {
        private const val TAG = "AdVaultVpn"
        const val ACTION_START = "com.advault.vpn.START"
        const val ACTION_STOP = "com.advault.vpn.STOP"
        const val ACTION_RELOAD = "com.advault.vpn.RELOAD"

        /** Only DNS enters the tunnel; a large MTU lets big (TCP-fallback) answers through in one datagram. */
        const val MTU = 16_384
        const val V6_LOCAL = "fd66:6164:7661::1"
        const val V6_DNS = "fd66:6164:7661::2"

        fun intent(context: Context, action: String) = Intent(context, AdVaultVpnService::class.java).setAction(action)
    }
}

/** Private IPv4 addresses of the tunnel; picks a /24 that no existing interface uses. */
internal data class TunAddresses(val v4Local: String, val v4Dns: String) {
    companion object {
        private val CANDIDATES = listOf("10.111.222", "172.31.222", "192.168.222", "10.222.111")

        fun choose(): TunAddresses {
            val inUse = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .flatMap { it.interfaceAddresses.orEmpty() }
                    .mapNotNull { (it.address as? Inet4Address)?.hostAddress?.substringBeforeLast('.') }
                    .toSet()
            } catch (_: Exception) { emptySet() }
            val prefix = CANDIDATES.firstOrNull { it !in inUse } ?: CANDIDATES.first()
            return TunAddresses("$prefix.1", "$prefix.2")
        }
    }
}
