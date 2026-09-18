package com.ben.ankerbattery.protocol.goalzero

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.model.PortReading
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Goal Zero Yeti Protocol Layer (Local REST & BLE Direct Connect).
 * Supports Yeti 1000X, 1500X, 3000X, 6000X, and Yeti PRO 4000 series.
 */
object GoalZeroProtocol {
    val GOAL_ZERO_SERVICE: UUID = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR: UUID = UUID.fromString("0000fe5a-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR: UUID = UUID.fromString("0000fe5b-0000-1000-8000-00805f9b34fb")
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun isGoalZero(name: String?, serviceUuids: List<UUID>): Boolean {
        val n = name.orEmpty().lowercase(Locale.US)
        return n.contains("goal zero") || n.contains("yeti") || n.contains("gz-") ||
               GOAL_ZERO_SERVICE in serviceUuids
    }

    /**
     * Builds Goal Zero Query status packet (equivalent to GET /state)
     */
    fun buildStatusQueryPacket(): ByteArray {
        return byteArrayOf(0x02.toByte(), 0x53.toByte(), 0x54.toByte(), 0x41.toByte(), 0x54.toByte(), 0x03.toByte()) // STX "STAT" ETX
    }

    fun parseResponse(payload: ByteArray, current: BatteryTelemetry): BatteryTelemetry {
        try {
            val text = String(payload, Charsets.UTF_8)
            if (text.trimStart().startsWith("{")) {
                val root = JSONObject(text)
                val soc = root.optDouble("socPercent", root.optDouble("batteryPercentage", current.batteryPercent ?: 0.0))
                val inputWatts = root.optDouble("wattsIn", root.optDouble("inputWatts", 0.0))
                val outputWatts = root.optDouble("wattsOut", root.optDouble("outputWatts", 0.0))
                val timeRemaining = root.optInt("timeRemaining", current.remainingMinutes ?: 0)
                val temp = root.optDouble("temperature", current.temperatureC ?: 0.0)

                val ports = listOf(
                    PortReading("AC Out", watts = root.optDouble("acOutWatts", 0.0)),
                    PortReading("12V DC Out", watts = root.optDouble("12vOutWatts", 0.0)),
                    PortReading("USB Out", watts = root.optDouble("usbOutWatts", 0.0))
                )

                return current.copy(
                    batteryPercent = soc,
                    totalInputW = inputWatts,
                    totalOutputW = outputWatts,
                    remainingMinutes = timeRemaining,
                    temperatureC = if (temp != 0.0) temp else current.temperatureC,
                    ports = ports,
                    packetsReceived = current.packetsReceived + 1,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            }
        } catch (_: Throwable) {}

        // Binary frame parsing
        if (payload.size >= 8) {
            val soc = (payload[2].toInt() and 0xFF).coerceIn(0, 100).toDouble()
            val inW = (((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)).toDouble()
            val outW = (((payload[5].toInt() and 0xFF) shl 8) or (payload[6].toInt() and 0xFF)).toDouble()
            return current.copy(
                batteryPercent = soc,
                totalInputW = inW,
                totalOutputW = outW,
                packetsReceived = current.packetsReceived + 1,
                lastUpdatedMs = System.currentTimeMillis()
            )
        }

        return current
    }
}
