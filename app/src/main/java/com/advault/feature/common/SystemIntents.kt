package com.advault.feature.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Helpers for opening system settings screens with graceful fallbacks. */
object SystemIntents {
    /** Undocumented but present on AOSP/Pixel/Samsung; falls back to network settings. */
    private const val ACTION_PRIVATE_DNS = "android.settings.PRIVATE_DNS_SETTINGS"

    /** Tries each intent in order; returns true if one launched. */
    fun launchFirst(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    fun openPrivateDnsSettings(context: Context) = launchFirst(
        context,
        Intent(ACTION_PRIVATE_DNS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun openVpnSettings(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_VPN_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun openBatteryOptimisationSettings(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(context),
    )

    fun openAppNotificationSettings(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        appDetails(context),
    )

    fun openAppDetails(context: Context) = launchFirst(context, appDetails(context))

    fun openUrl(context: Context, url: String) = launchFirst(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    fun isIgnoringBatteryOptimisations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    fun copyToClipboard(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    val isSamsung: Boolean get() = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}
