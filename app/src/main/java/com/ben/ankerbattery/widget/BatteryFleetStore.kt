package com.ben.ankerbattery.widget

import android.content.Context
import com.ben.ankerbattery.R
import com.ben.ankerbattery.ble.AnkerBleManager
import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import org.json.JSONArray
import org.json.JSONObject

data class FleetDevice(
    val name: String,
    val modelName: String,
    val brand: String,
    val address: String,
    val rssi: Int,
    val connected: Boolean,
    val batteryPercent: Double?,
    val totalInputW: Double?,
    val totalOutputW: Double?,
    val imageRes: Int,
    val lastSeenMs: Long
)

sealed class FleetListItem {
    data class Header(val brandTitle: String, val count: Int) : FleetListItem()
    data class Device(val device: FleetDevice) : FleetListItem()
}

object BatteryFleetStore {
    private const val PREFS_NAME = "battery_fleet_storage"
    private const val KEY_FLEET_JSON = "fleet_devices_json"
    private const val KEY_CONNECTED_ADDR = "connected_address"

    fun brandFor(type: AnkerProtocol.DeviceType, name: String): String {
        return when {
            type.isUgreen || name.contains("ugreen", true) -> "UGREEN"
            type.isBluetti || name.contains("bluetti", true) -> "BLUETTI"
            type.isZendure || name.contains("zendure", true) -> "Zendure"
            type.isEcoFlow || name.contains("ecoflow", true) || name.contains("river", true) || name.contains("delta", true) -> "EcoFlow"
            type.isGoalZero || name.contains("goal", true) || name.contains("yeti", true) -> "Goal Zero"
            else -> "Anker"
        }
    }

    fun imageResFor(type: AnkerProtocol.DeviceType): Int {
        return when (type) {
            AnkerProtocol.DeviceType.UGREEN_NEXODE_165W, AnkerProtocol.DeviceType.UGREEN_GENERIC -> R.drawable.ugreen_nexode_20k
            AnkerProtocol.DeviceType.BLUETTI_POWER_STATION -> R.drawable.solix_c200
            AnkerProtocol.DeviceType.ZENDURE_SOLARFLOW -> R.drawable.solix_c200
            AnkerProtocol.DeviceType.ECOFLOW_DELTA -> R.drawable.solix_c200
            AnkerProtocol.DeviceType.GOAL_ZERO_YETI -> R.drawable.solix_c200
            AnkerProtocol.DeviceType.PRIME_20K -> R.drawable.prime_20k
            AnkerProtocol.DeviceType.PRIME_26K -> R.drawable.prime_26k
            AnkerProtocol.DeviceType.PRIME_27K -> R.drawable.prime_27k
            AnkerProtocol.DeviceType.SOLIX_C200 -> R.drawable.solix_c200
            AnkerProtocol.DeviceType.UNKNOWN -> R.drawable.prime_20k
        }
    }

    fun updateTelemetry(context: Context, telemetry: BatteryTelemetry) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentList = loadAllDevices(context).toMutableList()
        val type = AnkerProtocol.classifyDevice(telemetry.modelName.ifBlank { telemetry.deviceName })
        val brand = brandFor(type, telemetry.deviceName)
        val img = imageResFor(type)

        val idx = currentList.indexOfFirst { it.address == telemetry.address }
        val updatedDevice = FleetDevice(
            name = telemetry.deviceName,
            modelName = telemetry.modelName,
            brand = brand,
            address = telemetry.address,
            rssi = if (idx >= 0) currentList[idx].rssi else -55,
            connected = telemetry.connected,
            batteryPercent = telemetry.batteryPercent,
            totalInputW = telemetry.totalInputW,
            totalOutputW = telemetry.totalOutputW,
            imageRes = img,
            lastSeenMs = System.currentTimeMillis()
        )

        if (idx >= 0) {
            currentList[idx] = updatedDevice
        } else if (telemetry.address.isNotBlank()) {
            currentList.add(0, updatedDevice)
        }

        saveAllDevices(context, currentList)
        if (telemetry.connected) {
            prefs.edit().putString(KEY_CONNECTED_ADDR, telemetry.address).apply()
        }
    }

    fun updateDiscoveredDevices(context: Context, discovered: List<AnkerBleManager.FoundDevice>, activeTelemetry: BatteryTelemetry) {
        val currentList = loadAllDevices(context).toMutableList()
        val now = System.currentTimeMillis()

        for (item in discovered) {
            val idx = currentList.indexOfFirst { it.address == item.address }
            val brand = brandFor(item.type, item.name)
            val img = imageResFor(item.type)
            val isConnected = activeTelemetry.connected && activeTelemetry.address == item.address

            val device = FleetDevice(
                name = item.name,
                modelName = item.modelName,
                brand = brand,
                address = item.address,
                rssi = item.rssi,
                connected = isConnected,
                batteryPercent = if (isConnected) activeTelemetry.batteryPercent else (if (idx >= 0) currentList[idx].batteryPercent else null),
                totalInputW = if (isConnected) activeTelemetry.totalInputW else (if (idx >= 0) currentList[idx].totalInputW else null),
                totalOutputW = if (isConnected) activeTelemetry.totalOutputW else (if (idx >= 0) currentList[idx].totalOutputW else null),
                imageRes = img,
                lastSeenMs = now
            )

            if (idx >= 0) {
                currentList[idx] = device
            } else {
                currentList.add(device)
            }
        }

        saveAllDevices(context, currentList)
    }

    fun loadAllDevices(context: Context): List<FleetDevice> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_FLEET_JSON, null) ?: return defaultSampleDevices()
        val list = mutableListOf<FleetDevice>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    FleetDevice(
                        name = obj.optString("name"),
                        modelName = obj.optString("modelName"),
                        brand = obj.optString("brand", "Anker"),
                        address = obj.optString("address"),
                        rssi = obj.optInt("rssi", -60),
                        connected = obj.optBoolean("connected", false),
                        batteryPercent = if (obj.has("batteryPercent")) obj.getDouble("batteryPercent") else null,
                        totalInputW = if (obj.has("totalInputW")) obj.getDouble("totalInputW") else null,
                        totalOutputW = if (obj.has("totalOutputW")) obj.getDouble("totalOutputW") else null,
                        imageRes = obj.optInt("imageRes", R.drawable.prime_20k),
                        lastSeenMs = obj.optLong("lastSeenMs", 0L)
                    )
                )
            }
        } catch (_: Throwable) {
            return defaultSampleDevices()
        }
        return if (list.isEmpty()) defaultSampleDevices() else list
    }

    private fun saveAllDevices(context: Context, devices: List<FleetDevice>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (d in devices) {
            val obj = JSONObject().apply {
                put("name", d.name)
                put("modelName", d.modelName)
                put("brand", d.brand)
                put("address", d.address)
                put("rssi", d.rssi)
                put("connected", d.connected)
                d.batteryPercent?.let { put("batteryPercent", it) }
                d.totalInputW?.let { put("totalInputW", it) }
                d.totalOutputW?.let { put("totalOutputW", it) }
                put("imageRes", d.imageRes)
                put("lastSeenMs", d.lastSeenMs)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_FLEET_JSON, array.toString()).apply()
    }

    fun getGroupedFleet(context: Context): List<FleetListItem> {
        val devices = loadAllDevices(context)
        val brandOrder = listOf("Anker", "UGREEN", "EcoFlow", "BLUETTI", "Zendure", "Goal Zero", "Other")
        val items = mutableListOf<FleetListItem>()

        for (brand in brandOrder) {
            val matching = devices.filter { it.brand.equals(brand, ignoreCase = true) }
            if (matching.isNotEmpty()) {
                items.add(FleetListItem.Header(brand.uppercase() + " BATTERIES", matching.size))
                matching.sortedByDescending { it.connected }.forEach {
                    items.add(FleetListItem.Device(it))
                }
            }
        }

        val handled = brandOrder.map { it.lowercase() }.toSet()
        val remaining = devices.filterNot { it.brand.lowercase() in handled }
        if (remaining.isNotEmpty()) {
            items.add(FleetListItem.Header("OTHER BATTERIES", remaining.size))
            remaining.forEach { items.add(FleetListItem.Device(it)) }
        }

        return items
    }

    private fun defaultSampleDevices(): List<FleetDevice> {
        return listOf(
            FleetDevice(
                name = "Anker Prime 20K",
                modelName = "20K Prime Power Bank",
                brand = "Anker",
                address = "7C:E9:13:68:48:52",
                rssi = -48,
                connected = true,
                batteryPercent = 88.0,
                totalInputW = 0.0,
                totalOutputW = 42.5,
                imageRes = R.drawable.prime_20k,
                lastSeenMs = System.currentTimeMillis()
            ),
            FleetDevice(
                name = "UGREEN Nexode 20000",
                modelName = "Nexode 165W 20000mAh",
                brand = "UGREEN",
                address = "C4:64:E3:92:11:08",
                rssi = -62,
                connected = false,
                batteryPercent = 94.0,
                totalInputW = 0.0,
                totalOutputW = 0.0,
                imageRes = R.drawable.ugreen_nexode_20k,
                lastSeenMs = System.currentTimeMillis()
            ),
            FleetDevice(
                name = "Anker SOLIX C200",
                modelName = "SOLIX C200 Portable Station",
                brand = "Anker",
                address = "50:8A:E2:31:09:44",
                rssi = -68,
                connected = false,
                batteryPercent = 75.0,
                totalInputW = 120.0,
                totalOutputW = 18.0,
                imageRes = R.drawable.solix_c200,
                lastSeenMs = System.currentTimeMillis()
            )
        )
    }
}
