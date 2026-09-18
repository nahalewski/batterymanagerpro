package com.ben.ankerbattery.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ben.ankerbattery.R
import java.util.Locale

class BatteryFleetWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return BatteryFleetRemoteViewsFactory(applicationContext)
    }
}

class BatteryFleetRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<FleetListItem> = emptyList()

    override fun onCreate() {
        items = BatteryFleetStore.getGroupedFleet(context)
    }

    override fun onDataSetChanged() {
        items = BatteryFleetStore.getGroupedFleet(context)
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewTypeCount(): Int = 2

    override fun getViewAt(position: Int): RemoteViews? {
        val item = items.getOrNull(position) ?: return null

        return when (item) {
            is FleetListItem.Header -> {
                val views = RemoteViews(context.packageName, R.layout.widget_fleet_header)
                views.setTextViewText(R.id.fleet_header_title, item.brandTitle)
                val countText = "${item.count} " + (if (item.count == 1) "device" else "devices")
                views.setTextViewText(R.id.fleet_header_count, countText)
                views
            }
            is FleetListItem.Device -> {
                val d = item.device
                val views = RemoteViews(context.packageName, R.layout.widget_fleet_item)
                views.setImageViewResource(R.id.fleet_item_img, d.imageRes)

                val displayName = d.modelName.ifBlank { d.name.ifBlank { "Battery" } }
                views.setTextViewText(R.id.fleet_item_name, displayName)

                val pctText = d.batteryPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--%"
                views.setTextViewText(R.id.fleet_item_pct, pctText)

                if (d.connected) {
                    views.setTextViewText(R.id.fleet_item_status, "● Live")
                    views.setTextColor(R.id.fleet_item_status, Color.rgb(0, 245, 170))
                } else {
                    views.setTextViewText(R.id.fleet_item_status, "○ Nearby")
                    views.setTextColor(R.id.fleet_item_status, Color.rgb(142, 176, 194))
                }

                val pwrText = when {
                    (d.totalInputW ?: 0.0) > 0.0 -> String.format(Locale.US, "IN %.0fW", d.totalInputW)
                    (d.totalOutputW ?: 0.0) > 0.0 -> String.format(Locale.US, "OUT %.0fW", d.totalOutputW)
                    d.rssi != 0 -> "${d.rssi} dBm"
                    else -> "Ready"
                }
                views.setTextViewText(R.id.fleet_item_power, pwrText)

                val fillInIntent = Intent().apply {
                    putExtra("target_address", d.address)
                    putExtra("target_brand", d.brand)
                }
                views.setOnClickFillInIntent(R.id.fleet_item_root, fillInIntent)
                views
            }
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
