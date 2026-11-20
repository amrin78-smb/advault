package com.advault.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.advault.core.common.ApplicationScope
import com.advault.core.preferences.SettingsRepository
import com.advault.data.models.ProtectionState
import com.advault.data.models.VpnDiagnostics
import com.advault.data.repository.ProtectionController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide entry point for protection. Holds the state/diagnostics flows that [AdVaultVpnService] updates.
 * Commands go to the running service instance directly when it exists, otherwise via intents.
 */
@Singleton
class ProtectionControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : ProtectionController {

    private val _state = MutableStateFlow<ProtectionState>(ProtectionState.Stopped)
    override val state: StateFlow<ProtectionState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(VpnDiagnostics())
    override val diagnostics: StateFlow<VpnDiagnostics> = _diagnostics.asStateFlow()

    @Volatile private var networkNotes: List<String> = emptyList()
    @Volatile private var engineNotes: List<String> = emptyList()

    /** Set by the service while it is alive. */
    @Volatile internal var activeService: AdVaultVpnService? = null

    override fun prepareIntent(): Intent? = VpnService.prepare(context)

    override fun start() {
        scope.launch { settings.setProtectionDesired(true) }
        val current = _state.value
        if (activeService != null && (current is ProtectionState.Running || current is ProtectionState.WaitingForNetwork)) {
            return // idempotent
        }
        _state.value = ProtectionState.Starting
        try {
            ContextCompat.startForegroundService(context, AdVaultVpnService.intent(context, AdVaultVpnService.ACTION_START))
        } catch (e: Exception) {
            Log.e(TAG, "Could not start VPN service", e)
            _state.value = ProtectionState.Error("Could not start protection: ${e.message}", recoverable = true)
        }
    }

    override fun stop() {
        scope.launch { settings.setProtectionDesired(false) }
        val svc = activeService
        if (svc != null) {
            svc.requestStop()
        } else {
            try {
                context.startService(AdVaultVpnService.intent(context, AdVaultVpnService.ACTION_STOP))
            } catch (_: Exception) { /* not running and cannot be started from background: already stopped */ }
            _state.value = ProtectionState.Stopped
        }
    }

    override fun reload() {
        activeService?.requestReload()
    }

    override fun refreshDiagnostics() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val notes = ArrayList<String>()
        var activeName = "No network"
        var privateDnsActive = false
        var privateDnsName: String? = null
        var otherVpn = false
        try {
            val myUid = Process.myUid()
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    // On Q+ the owner uid is only revealed to the VPN's owner; anything else is another app's VPN.
                    // getOwnerUid() is API 30+; on Android 10 any VPN while our service is down is someone else's.
                    val notOurs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) caps.ownerUid != myUid else false
                    if (notOurs || activeService == null) otherVpn = true
                }
            }
            // We are excluded from our own VPN, so our default network is the physical one.
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            if (caps != null) {
                activeName = describe(caps)
                val lp = cm.getLinkProperties(active)
                if (lp != null) {
                    privateDnsActive = lp.isPrivateDnsActive
                    privateDnsName = lp.privateDnsServerName
                }
            }
        } catch (e: Exception) {
            notes += "Could not read network state: ${e.message}"
        }
        if (otherVpn) notes += "Another app's VPN is active. Starting AdVault will disconnect it (Android allows one VPN at a time)."
        if (privateDnsActive && privateDnsName != null) {
            notes += "Private DNS is set to \"$privateDnsName\" (strict mode). Android sends DNS directly to it, bypassing AdVault filtering. Set Private DNS to Off or Automatic."
        }
        activeService?.let { svc ->
            if (svc.isAlwaysOn) notes += "AdVault is configured as the always-on VPN."
            if (svc.isLockdownEnabled) notes += "\"Block connections without VPN\" is enabled for AdVault."
        }
        networkNotes = notes
        _diagnostics.update {
            it.copy(
                activeNetwork = activeName,
                privateDnsActive = privateDnsActive,
                privateDnsServerName = privateDnsName,
                anotherVpnActive = otherVpn,
                notes = networkNotes + engineNotes,
            )
        }
    }

    // ---------------------------------------------------------------- service-facing API

    internal fun setState(s: ProtectionState) { _state.value = s }

    internal fun updateDiagnostics(engineNotes: List<String>? = null, block: (VpnDiagnostics) -> VpnDiagnostics) {
        if (engineNotes != null) this.engineNotes = engineNotes
        _diagnostics.update { block(it).copy(notes = networkNotes + this.engineNotes) }
    }

    internal fun incrementRestartCount() = _diagnostics.update { it.copy(restartCount = it.restartCount + 1) }

    internal fun setDesired(desired: Boolean) { scope.launch { settings.setProtectionDesired(desired) } }

    companion object {
        private const val TAG = "AdVaultCtrl"

        fun describe(caps: NetworkCapabilities): String = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth tethering"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    }
}
