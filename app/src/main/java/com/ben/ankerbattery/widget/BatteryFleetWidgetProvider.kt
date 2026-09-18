package com.ben.ankerbattery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.ben.ankerbattery.MainActivity
import com.ben.ankerbattery.R
import com.ben.ankerbattery.ble.AnkerBleManager
import com.ben.ankerbattery.model.BatteryTelemetry

class BatteryFleetWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateFleetWidget(context, appWidgetManager, appWidgetId)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun updateAllWidgets(
            context: Context,
            activeTelemetry: BatteryTelemetry,
            discovered: List<AnkerBleManager.FoundDevice> = emptyList()
        ) {
            BatteryFleetStore.updateTelemetry(context, activeTelemetry)
            if (discovered.isNotEmpty()) {
                BatteryFleetStore.updateDiscoveredDevices(context, discovered, activeTelemetry)
            }

            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, BatteryFleetWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(component) ?: return

            for (id in appWidgetIds) {
                updateFleetWidget(context, appWidgetManager, id)
            }
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_fleet_list)
        }

        fun updateFleetWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_battery_fleet)

            // Setup intent to bind RemoteViewsService with unique URI for widget ID
            val serviceIntent = Intent(context, BatteryFleetWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_fleet_list, serviceIntent)
            views.setEmptyView(R.id.widget_fleet_list, R.id.widget_fleet_empty)

            // Count badge
            val devices = BatteryFleetStore.loadAllDevices(context)
            val connectedCount = devices.count { it.connected }
            val countBadge = if (connectedCount > 0) {
                "● $connectedCount Connected"
            } else {
                "${devices.size} Discovered"
            }
            views.setTextViewText(R.id.widget_fleet_count_badge, countBadge)

            // PendingIntent Template for item clicks
            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val clickPendingIntent = PendingIntent.getActivity(
                context,
                0,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_fleet_list, clickPendingIntent)

            // Header bar click opens main app
            views.setOnClickPendingIntent(R.id.widget_fleet_header_bar, clickPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
