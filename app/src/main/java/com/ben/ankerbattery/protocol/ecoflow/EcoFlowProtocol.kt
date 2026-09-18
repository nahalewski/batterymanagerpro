package com.ben.ankerbattery.protocol.ecoflow

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.model.PortReading
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * EcoFlow Open API (Cloud/Local MQTT & REST) and BLE Protocol Layer.
 * Supports RIVER 2/3 series, DELTA 2, DELTA Pro, DELTA 3, Smart Generator, PowerStream.
 */
object EcoFlowProtocol {
    val ECOFLOW_SERVICE: UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR: UUID = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR: UUID = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun isEcoFlow(name: String?, serviceUuids: List<UUID>): Boolean {
        val n = name.orEmpty().lowercase(Locale.US)
        return n.contains("ecoflow") || n.contains("delta") || n.contains("river") ||
               n.contains("powerstream") || n.startsWith("ef-") || ECOFLOW_SERVICE in serviceUuids
    }

    /**
     * Builds EcoFlow Status Query Command (CmdFunc 0x20, CmdId 0x02)
     */
    fun buildStatusQueryPacket(): ByteArray {
        return byteArrayOf(
            0xAA.toByte(), 0x02.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x20.toByte(),
            0x02.toByte(), 0x00.toByte(), 0x00.toByte(), 0xCC.toByte()
        )
    }

    fun parseResponse(payload: ByteArray, current: BatteryTelemetry): BatteryTelemetry {
        // Try parsing JSON (EcoFlow Open API / MQTT format)
        try {
            val text = String(payload, Charsets.UTF_8)
            if (text.trimStart().startsWith("{")) {
                val json = JSONObject(text)
                val params = json.optJSONObject("params") ?: json.optJSONObject("data") ?: json

                val soc = when {
                    params.has("bms_bmsStatus.soc") -> params.optDouble("bms_bmsStatus.soc")
                    params.has("soc") -> params.optDouble("soc")
                    params.has("batterySoc") -> params.optDouble("batterySoc")
                    params.has("cmsBattSoc") -> params.optDouble("cmsBattSoc")
                    else -> current.batteryPercent
                }

                val inWatts = when {
                    params.has("inv.inputWatts") -> params.optDouble("inv.inputWatts")
                    params.has("inWatts") -> params.optDouble("inWatts")
                    params.has("wattsInSum") -> params.optDouble("wattsInSum")
                    else -> current.totalInputW
                }

                val outWatts = when {
                    params.has("inv.outputWatts") -> params.optDouble("inv.outputWatts")
                    params.has("outWatts") -> params.optDouble("outWatts")
                    params.has("wattsOutSum") -> params.optDouble("wattsOutSum")
                    else -> current.totalOutputW
                }

                val remainTime = when {
                    params.has("bms_bmsStatus.remainTime") -> params.optInt("bms_bmsStatus.remainTime")
                    params.has("remainTime") -> params.optInt("remainTime")
                    else -> current.remainingMinutes
                }

                val temp = when {
                    params.has("bms_bmsStatus.temp") -> params.optDouble("bms_bmsStatus.temp")
                    params.has("bms_emsStatus.temp") -> params.optDouble("bms_emsStatus.temp")
                    else -> current.temperatureC
                }

                return current.copy(
                    batteryPercent = soc,
                    totalInputW = inWatts,
                    totalOutputW = outWatts,
                    remainingMinutes = remainTime,
                    temperatureC = temp,
                    packetsReceived = current.packetsReceived + 1,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            }
        } catch (_: Throwable) {}

        // Binary frame parsing
        if (payload.size >= 12 && payload[0] == 0xAA.toByte()) {
            val soc = (payload[5].toInt() and 0xFF).coerceIn(0, 100).toDouble()
            val inW = (((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)).toDouble()
            val outW = (((payload[8].toInt() and 0xFF) shl 8) or (payload[9].toInt() and 0xFF)).toDouble()
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
