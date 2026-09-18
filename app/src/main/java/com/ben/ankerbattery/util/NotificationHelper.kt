package com.ben.ankerbattery.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.ben.ankerbattery.R
import com.ben.ankerbattery.model.BatteryTelemetry

object NotificationHelper {
    private const val CHANNEL_ID = "firmware_updates"
    private const val CHANNEL_NAME = "Device Firmware Updates"
    private var lastNotifiedDevice: String? = null

    fun notifyFirmwareUpdate(context: Context, telemetry: BatteryTelemetry) {
        if (!telemetry.updateAvailable) return
        if (lastNotifiedDevice == telemetry.address) return
        lastNotifiedDevice = telemetry.address

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when firmware updates are available with official app safety disclaimer."
            }
            nm.createNotificationChannel(channel)
        }

        val officialApp = telemetry.officialAppName ?: "official manufacturer"
        val pkg = telemetry.officialAppPackage

        // Intent to launch official app or Play Store
        val launchIntent = if (pkg != null && context.packageManager.getLaunchIntentForPackage(pkg) != null) {
            context.packageManager.getLaunchIntentForPackage(pkg)
        } else if (pkg != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
        } else {
            Intent(context, com.ben.ankerbattery.MainActivity::class.java)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            1002,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Update Available • ${telemetry.modelName}"
        val body = "A new firmware update is available. Use the official $officialApp app to safely update your battery."

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(R.drawable.widget_batt_100)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText("$body\n\n⚠️ DISCLAIMER: This app does not flash firmware directly to protect your device warranty."))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            nm.notify(101, notification)
        } catch (_: Throwable) {}
    }
}
