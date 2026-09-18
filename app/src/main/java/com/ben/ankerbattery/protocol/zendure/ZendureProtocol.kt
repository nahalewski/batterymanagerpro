package com.ben.ankerbattery.protocol.zendure

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.model.PortReading
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Zendure ZenSDK / Local MQTT / BLE Protocol Layer.
 * Supports SolarFlow (Hub 1200 / Hub 2000), SuperBase Pro, SuperBase V series.
 */
object ZendureProtocol {
    val ZENDURE_SERVICE: UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR: UUID = UUID.fromString("0000c305-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR: UUID = UUID.fromString("0000c304-0000-1000-8000-00805f9b34fb")
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun isZendure(name: String?, serviceUuids: List<UUID>): Boolean {
        val n = name.orEmpty().lowercase(Locale.US)
        return n.contains("zendure") || n.contains("solarflow") || n.contains("superbase") ||
               n.contains("sbp") || n.contains("hub1200") || n.contains("hub2000") ||
               ZENDURE_SERVICE in serviceUuids
    }

    /**
     * Builds ZenSDK JSON Status Request
     */
    fun buildStatusQueryPacket(): ByteArray {
        val json = JSONObject().apply {
            put("messageId", System.currentTimeMillis().toString())
            put("method", "device.getProperties")
            put("params", JSONObject())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun parseResponse(payload: ByteArray, current: BatteryTelemetry): BatteryTelemetry {
        return try {
            val text = String(payload, Charsets.UTF_8)
            if (!text.trimStart().startsWith("{")) return current
            val root = JSONObject(text)
            val props = root.optJSONObject("properties") ?: root.optJSONObject("data") ?: root

            val soc = when {
                props.has("electricLevel") -> props.optDouble("electricLevel")
                props.has("soc") -> props.optDouble("soc")
                props.has("batteryPercent") -> props.optDouble("batteryPercent")
                else -> current.batteryPercent
            }

            val solarInput = props.optDouble("solarInputPower", props.optDouble("pvPower", 0.0))
            val acInput = props.optDouble("acInputPower", props.optDouble("gridPower", 0.0))
            val outputPower = props.optDouble("outputPackPower", props.optDouble("outputHomePower", props.optDouble("acOutputPower", 0.0)))
            val temp = if (props.has("hyperTmp")) props.optDouble("hyperTmp") else current.temperatureC

            val totalIn = solarInput + acInput
            val ports = listOf(
                PortReading("Solar PV In", watts = solarInput),
                PortReading("AC Grid In", watts = acInput),
                PortReading("AC / Home Out", watts = outputPower)
            )

            current.copy(
                batteryPercent = soc,
                totalInputW = totalIn,
                totalOutputW = outputPower,
                temperatureC = temp,
                ports = ports,
                packetsReceived = current.packetsReceived + 1,
                lastUpdatedMs = System.currentTimeMillis()
            )
        } catch (_: Throwable) {
            current
        }
    }
}
