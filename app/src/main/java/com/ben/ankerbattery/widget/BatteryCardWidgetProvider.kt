package com.ben.ankerbattery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.ben.ankerbattery.MainActivity
import com.ben.ankerbattery.R
import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import java.util.Locale

class BatteryCardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val telemetry = loadCachedTelemetry(context)
        for (appWidgetId in appWidgetIds) {
            updateCardWidget(context, appWidgetManager, appWidgetId, telemetry)
        }
    }

    companion object {
        private const val PREFS_NAME = "battery_card_widget_prefs"
        private const val KEY_MODEL = "model_name"
        private const val KEY_DEV = "dev_name"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_PCT = "pct"
        private const val KEY_IN_W = "in_w"
        private const val KEY_OUT_W = "out_w"
        private const val KEY_TEMP = "temp"
        private const val KEY_REM_MIN = "rem_min"

        fun updateAllWidgets(context: Context, telemetry: BatteryTelemetry) {
            saveCachedTelemetry(context, telemetry)
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, BatteryCardWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(component) ?: return
            for (id in appWidgetIds) {
                updateCardWidget(context, appWidgetManager, id, telemetry)
            }
        }

        private fun saveCachedTelemetry(context: Context, t: BatteryTelemetry) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(KEY_MODEL, t.modelName)
                putString(KEY_DEV, t.deviceName)
                putBoolean(KEY_CONNECTED, t.connected)
                t.batteryPercent?.let { putFloat(KEY_PCT, it.toFloat()) } ?: remove(KEY_PCT)
                t.totalInputW?.let { putFloat(KEY_IN_W, it.toFloat()) } ?: remove(KEY_IN_W)
                t.totalOutputW?.let { putFloat(KEY_OUT_W, it.toFloat()) } ?: remove(KEY_OUT_W)
                t.temperatureC?.let { putFloat(KEY_TEMP, it.toFloat()) } ?: remove(KEY_TEMP)
                t.remainingMinutes?.let { putInt(KEY_REM_MIN, it) } ?: remove(KEY_REM_MIN)
                apply()
            }
        }

        private fun loadCachedTelemetry(context: Context): BatteryTelemetry {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return BatteryTelemetry(
                modelName = prefs.getString(KEY_MODEL, "20K Prime Power Bank") ?: "20K Prime Power Bank",
                deviceName = prefs.getString(KEY_DEV, "Anker Prime") ?: "Anker Prime",
                connected = prefs.getBoolean(KEY_CONNECTED, true),
                batteryPercent = if (prefs.contains(KEY_PCT)) prefs.getFloat(KEY_PCT, 88f).toDouble() else 88.0,
                totalInputW = if (prefs.contains(KEY_IN_W)) prefs.getFloat(KEY_IN_W, 0f).toDouble() else 0.0,
                totalOutputW = if (prefs.contains(KEY_OUT_W)) prefs.getFloat(KEY_OUT_W, 42.5f).toDouble() else 42.5,
                temperatureC = if (prefs.contains(KEY_TEMP)) prefs.getFloat(KEY_TEMP, 28.0f).toDouble() else 28.0,
                remainingMinutes = if (prefs.contains(KEY_REM_MIN)) prefs.getInt(KEY_REM_MIN, 135) else 135
            )
        }

        fun updateCardWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            telemetry: BatteryTelemetry
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_battery_card)
            val type = AnkerProtocol.classifyDevice(telemetry.modelName.ifBlank { telemetry.deviceName })
            val imgRes = BatteryFleetStore.imageResFor(type)
            views.setImageViewResource(R.id.widget_card_img, imgRes)

            val brand = BatteryFleetStore.brandFor(type, telemetry.deviceName)
            views.setTextViewText(R.id.widget_card_brand, brand.uppercase())

            val modelText = telemetry.modelName.ifBlank { telemetry.deviceName.ifBlank { "Power Station" } }
            views.setTextViewText(R.id.widget_card_model, modelText)

            val pct = telemetry.batteryPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--%"
            views.setTextViewText(R.id.widget_card_pct, pct)

            if (telemetry.connected) {
                views.setTextViewText(R.id.widget_card_status, "● Live Telemetry Stream")
                views.setTextColor(R.id.widget_card_status, Color.rgb(0, 245, 170))
            } else {
                views.setTextViewText(R.id.widget_card_status, "○ Disconnected")
                views.setTextColor(R.id.widget_card_status, Color.rgb(142, 176, 194))
            }

            val inW = telemetry.totalInputW?.let { String.format(Locale.US, "%.1f W", it) } ?: "0.0 W"
            views.setTextViewText(R.id.widget_card_input_w, inW)

            val outW = telemetry.totalOutputW?.let { String.format(Locale.US, "%.1f W", it) } ?: "0.0 W"
            views.setTextViewText(R.id.widget_card_output_w, outW)

            val extra = buildString {
                telemetry.temperatureC?.let { append(String.format(Locale.US, "%.1f°C", it)) }
                telemetry.remainingMinutes?.let { min ->
                    if (isNotEmpty()) append("  •  ")
                    append("${min / 60}h ${min % 60}m left")
                }
                if (isEmpty()) append(if (telemetry.connected) "Connected" else "Tap to connect")
            }
            views.setTextViewText(R.id.widget_card_extra, extra)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_card_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
