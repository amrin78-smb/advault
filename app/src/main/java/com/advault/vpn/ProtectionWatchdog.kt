package com.advault.vpn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.advault.R
import com.advault.core.preferences.SettingsRepository
import com.advault.data.models.ProtectionState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps protection running when the user wants it on.
 *
 * Android tears down a VpnService whose process is killed (low memory, OEM battery savers) and only
 * restarts it by itself when Always-on VPN is enabled. Without this, protection would silently stay off.
 * The watchdog re-checks on every process start (app open, boot, WorkManager job) and every 15 minutes.
 * If Android forbids a background start, it posts a "tap to resume" notification instead — it never
 * pretends protection is running.
 */
@Singleton
class ProtectionWatchdog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val controller: ProtectionControllerImpl,
) {
    /** Returns true if protection is (now) running or not wanted. */
    suspend fun ensureRunning(): Boolean {
        if (!settings.current().protectionDesired) return true
        val s = controller.state.value
        if (controller.activeService != null &&
            (s is ProtectionState.Running || s is ProtectionState.WaitingForNetwork || s is ProtectionState.Starting)
        ) return true
        if (VpnService.prepare(context) != null) {
            // Consent revoked (e.g. another VPN took over). User must act; do not auto-restart.
            notifyStopped("VPN permission was removed. Open AdVault to turn protection back on.")
            return false
        }
        return try {
            ContextCompat.startForegroundService(context, AdVaultVpnService.intent(context, AdVaultVpnService.ACTION_START))
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            true
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException on Android 12+ from background
            Log.w(TAG, "Background restart not allowed", e)
            notifyStopped("Android stopped AdVault in the background. Tap to resume protection.")
            false
        }
    }

    fun schedule() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ProtectionWatchdogWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    private fun notifyStopped(text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "Protection alerts", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Tells you when protection stopped unexpectedly." },
            )
        }
        val n = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("Protection is off")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(VpnNotifications.contentIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "AdVaultWatchdog"
        const val WORK_NAME = "advault-protection-watchdog"
        private const val ALERT_CHANNEL = "advault_alerts"
        private const val NOTIFICATION_ID = 4711
    }
}

@HiltWorker
class ProtectionWatchdogWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val watchdog: ProtectionWatchdog,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        watchdog.ensureRunning()
        return Result.success()
    }
}
