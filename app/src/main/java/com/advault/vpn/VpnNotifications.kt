package com.advault.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.advault.R
import com.advault.app.MainActivity

internal object VpnNotifications {
    const val CHANNEL_ID = "advault_protection"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while AdVault's local DNS filter is active"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun build(context: Context, title: String, text: String?): Notification {
        val stop = PendingIntent.getService(
            context, 1, AdVaultVpnService.intent(context, AdVaultVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(contentIntent(context))
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(context: Context, notification: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification) }
    }
}
