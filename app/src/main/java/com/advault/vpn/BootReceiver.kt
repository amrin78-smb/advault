package com.advault.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import com.advault.core.preferences.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Restarts protection after boot or an app update when the user wants it on, "start on boot" is enabled and
 * VPN consent is still valid. Uses goAsync() for the DataStore read.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun settings(): SettingsRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = withTimeoutOrNull(8_000) {
                    EntryPointAccessors.fromApplication(app, BootEntryPoint::class.java).settings().current()
                }
                if (settings != null && settings.startOnBoot && settings.protectionDesired && VpnService.prepare(app) == null) {
                    ContextCompat.startForegroundService(app, AdVaultVpnService.intent(app, AdVaultVpnService.ACTION_START))
                }
            } catch (e: Exception) {
                Log.e("AdVaultBoot", "Could not restore protection", e)
            } finally {
                pending.finish()
            }
        }
    }
}
