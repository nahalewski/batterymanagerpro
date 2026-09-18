package com.ben.ankerbattery.data

import android.content.Context
import com.ben.ankerbattery.model.BatteryTelemetry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageLogger(private val context: Context) {
    private val file: File get() = File(context.filesDir, "anker_usage.csv")

    fun append(t: BatteryTelemetry) {
        if (!file.exists()) {
            file.writeText("timestamp,device,model,address,battery_percent,temp_c,input_w,output_w,packet_count,last_packet_hex\n")
        }
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val row = listOf(
            ts,
            csv(t.deviceName),
            csv(t.modelName),
            csv(t.address),
            t.batteryPercent?.toString().orEmpty(),
            t.temperatureC?.toString().orEmpty(),
            t.totalInputW?.toString().orEmpty(),
            t.totalOutputW?.toString().orEmpty(),
            t.packetsReceived.toString(),
            csv(t.lastPacketHex.take(1024))
        ).joinToString(",") + "\n"
        file.appendText(row)
    }

    fun historyFile(): File = file

    fun hasHistory(): Boolean = file.exists() && file.length() > 0

    fun readRecentLines(limit: Int = 12): List<String> {
        if (!file.exists()) return emptyList()
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()
        return lines.drop(1).takeLast(limit).reversed()
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
