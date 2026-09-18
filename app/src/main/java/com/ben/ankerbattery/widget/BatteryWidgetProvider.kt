package com.ben.ankerbattery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.ben.ankerbattery.MainActivity
import com.ben.ankerbattery.R
import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import java.util.Locale

class BatteryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val cached = loadCachedTelemetry(context)
        for (appWidgetId in appWidgetIds) {
            updateWidgetView(context, appWidgetManager, appWidgetId, cached)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        val cached = loadCachedTelemetry(context)
        updateWidgetView(context, appWidgetManager, appWidgetId, cached)
    }

    companion object {
        private const val PREFS_NAME = "battery_widget_prefs"
        private const val KEY_DEV_NAME = "dev_name"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_BATT_PCT = "batt_pct"
        private const val KEY_TEMP_C = "temp_c"
        private const val KEY_IN_W = "in_w"
        private const val KEY_OUT_W = "out_w"
        private const val KEY_REM_MIN = "rem_min"
        private const val KEY_UPDATED_MS = "updated_ms"

        fun updateAllWidgets(context: Context, telemetry: BatteryTelemetry) {
            saveCachedTelemetry(context, telemetry)
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, BatteryWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(component) ?: return
            for (id in appWidgetIds) {
                updateWidgetView(context, appWidgetManager, id, telemetry)
            }
        }

        private fun saveCachedTelemetry(context: Context, t: BatteryTelemetry) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(KEY_DEV_NAME, t.deviceName)
                putString(KEY_MODEL_NAME, t.modelName)
                putBoolean(KEY_CONNECTED, t.connected)
                t.batteryPercent?.let { putFloat(KEY_BATT_PCT, it.toFloat()) } ?: remove(KEY_BATT_PCT)
                t.temperatureC?.let { putFloat(KEY_TEMP_C, it.toFloat()) } ?: remove(KEY_TEMP_C)
                t.totalInputW?.let { putFloat(KEY_IN_W, it.toFloat()) } ?: remove(KEY_IN_W)
                t.totalOutputW?.let { putFloat(KEY_OUT_W, it.toFloat()) } ?: remove(KEY_OUT_W)
                t.remainingMinutes?.let { putInt(KEY_REM_MIN, it) } ?: remove(KEY_REM_MIN)
                putLong(KEY_UPDATED_MS, t.lastUpdatedMs)
                apply()
            }
        }

        private fun loadCachedTelemetry(context: Context): BatteryTelemetry {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val devName = prefs.getString(KEY_DEV_NAME, "Battery") ?: "Battery"
            val modelName = prefs.getString(KEY_MODEL_NAME, "20K Prime Power Bank") ?: "20K Prime Power Bank"
            val connected = prefs.getBoolean(KEY_CONNECTED, true)
            val battPct = if (prefs.contains(KEY_BATT_PCT)) prefs.getFloat(KEY_BATT_PCT, 88f).toDouble() else 88.0
            val tempC = if (prefs.contains(KEY_TEMP_C)) prefs.getFloat(KEY_TEMP_C, 28f).toDouble() else 28.0
            val inW = if (prefs.contains(KEY_IN_W)) prefs.getFloat(KEY_IN_W, 0f).toDouble() else 0.0
            val outW = if (prefs.contains(KEY_OUT_W)) prefs.getFloat(KEY_OUT_W, 42f).toDouble() else 42.0
            val remMin = if (prefs.contains(KEY_REM_MIN)) prefs.getInt(KEY_REM_MIN, 135) else 135
            val updatedMs = prefs.getLong(KEY_UPDATED_MS, 0L)

            return BatteryTelemetry(
                deviceName = devName,
                modelName = modelName,
                connected = connected,
                batteryPercent = battPct,
                temperatureC = tempC,
                totalInputW = inW,
                totalOutputW = outW,
                remainingMinutes = remMin,
                lastUpdatedMs = updatedMs
            )
        }

        private fun getBatteryDrawable(percent: Double?): Int {
            if (percent == null) return R.drawable.widget_batt_80
            val clamped = percent.coerceIn(0.0, 100.0)
            val step = ((Math.round(clamped / 10.0) * 10).toInt()).coerceIn(0, 100)
            return when (step) {
                0 -> R.drawable.widget_batt_0
                10 -> R.drawable.widget_batt_10
                20 -> R.drawable.widget_batt_20
                30 -> R.drawable.widget_batt_30
                40 -> R.drawable.widget_batt_40
                50 -> R.drawable.widget_batt_50
                60 -> R.drawable.widget_batt_60
                70 -> R.drawable.widget_batt_70
                80 -> R.drawable.widget_batt_80
                90 -> R.drawable.widget_batt_90
                100 -> R.drawable.widget_batt_100
                else -> R.drawable.widget_batt_80
            }
        }

        private fun buildSmallViews(context: Context, telemetry: BatteryTelemetry): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_battery_small)
            val drawableRes = getBatteryDrawable(telemetry.batteryPercent)
            views.setImageViewResource(R.id.widget_battery_icon, drawableRes)

            val pctText = telemetry.batteryPercent?.let {
                String.format(Locale.US, "%.0f%%", it)
            } ?: "--%"
            views.setTextViewText(R.id.widget_battery_pct, pctText)

            val displayName = telemetry.modelName.ifBlank { telemetry.deviceName.ifBlank { "Battery" } }
            views.setTextViewText(R.id.widget_device_name, displayName)

            if (telemetry.connected) {
                views.setTextViewText(R.id.widget_status, "● Live")
                views.setTextColor(R.id.widget_status, Color.rgb(0, 245, 170))
            } else {
                views.setTextViewText(R.id.widget_status, "○ Offline")
                views.setTextColor(R.id.widget_status, Color.rgb(142, 176, 194))
            }

            val pwr = when {
                (telemetry.totalInputW ?: 0.0) > 0.0 -> String.format(Locale.US, "IN %.0fW", telemetry.totalInputW)
                (telemetry.totalOutputW ?: 0.0) > 0.0 -> String.format(Locale.US, "OUT %.0fW", telemetry.totalOutputW)
                else -> "0W"
            }
            views.setTextViewText(R.id.widget_power_compact, pwr)

            attachClickIntent(context, views)
            return views
        }

        private fun buildLargeViews(context: Context, telemetry: BatteryTelemetry): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_battery_large)
            val type = AnkerProtocol.classifyDevice(telemetry.modelName.ifBlank { telemetry.deviceName })
            val imgRes = BatteryFleetStore.imageResFor(type)
            views.setImageViewResource(R.id.widget_battery_device_img, imgRes)

            val brand = BatteryFleetStore.brandFor(type, telemetry.deviceName)
            views.setTextViewText(R.id.widget_brand_badge, brand.uppercase())

            val displayName = telemetry.modelName.ifBlank { telemetry.deviceName.ifBlank { "Power Station" } }
            views.setTextViewText(R.id.widget_device_name, displayName)

            val pctText = telemetry.batteryPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--%"
            views.setTextViewText(R.id.widget_battery_pct_badge, pctText)

            if (telemetry.connected) {
                views.setTextViewText(R.id.widget_status, "● Connected — Live Telemetry")
                views.setTextColor(R.id.widget_status, Color.rgb(0, 245, 170))
            } else {
                views.setTextViewText(R.id.widget_status, "○ Disconnected")
                views.setTextColor(R.id.widget_status, Color.rgb(142, 176, 194))
            }

            val inWatts = telemetry.totalInputW?.let { String.format(Locale.US, "IN  %.0fW", it) } ?: "IN  0W"
            views.setTextViewText(R.id.widget_input_watts, inWatts)

            val outWatts = telemetry.totalOutputW?.let { String.format(Locale.US, "OUT  %.0fW", it) } ?: "OUT  0W"
            views.setTextViewText(R.id.widget_output_watts, outWatts)

            val extra = buildString {
                telemetry.temperatureC?.let { append(String.format(Locale.US, "%.1f°C", it)) }
                telemetry.remainingMinutes?.let { min ->
                    if (isNotEmpty()) append("  •  ")
                    append("${min / 60}h ${min % 60}m left")
                }
                if (isEmpty()) append(if (telemetry.connected) "Live telemetry active" else "Tap to connect")
            }
            views.setTextViewText(R.id.widget_extra_info, extra)

            attachClickIntent(context, views)
            return views
        }

        private fun attachClickIntent(context: Context, views: RemoteViews) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        }

        fun updateWidgetView(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            telemetry: BatteryTelemetry
        ) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)

            val small = buildSmallViews(context, telemetry)
            val large = buildLargeViews(context, telemetry)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Responsive views on Android 12+
                val viewMapping = mapOf(
                    SizeF(110f, 60f) to small,
                    SizeF(200f, 100f) to large
                )
                appWidgetManager.updateAppWidget(appWidgetId, RemoteViews(viewMapping))
            } else {
                // Pre-Android 12 size check
                val isSmall = minWidth < 190 || minHeight < 110
                appWidgetManager.updateAppWidget(appWidgetId, if (isSmall) small else large)
            }
        }
    }
}
