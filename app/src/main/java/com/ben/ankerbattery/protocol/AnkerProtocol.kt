package com.ben.ankerbattery.protocol

import com.ben.ankerbattery.model.BatteryTelemetry
import java.util.Locale
import java.util.UUID

/**
 * Read-only protocol helpers.
 *
 * Publicly documented / reverse-engineered Anker SOLIX / Prime BLE identifiers:
 * advertised service 0xFF09, telemetry characteristic ...0003 and command ...0002.
 * This app intentionally does NOT send device-control commands.
 */
object AnkerProtocol {
    enum class DeviceType {
        PRIME_20K,
        PRIME_26K,
        PRIME_27K,
        SOLIX_C200,
        UGREEN_NEXODE_165W,
        UGREEN_GENERIC,
        UNKNOWN;

        val isPrime: Boolean
            get() = this == PRIME_20K || this == PRIME_26K || this == PRIME_27K

        val isUgreen: Boolean
            get() = this == UGREEN_NEXODE_165W || this == UGREEN_GENERIC
    }

    val IDENTIFIER_SERVICE: UUID = UUID.fromString("0000ff09-0000-1000-8000-00805f9b34fb")
    val TELEMETRY_CHARACTERISTIC: UUID = UUID.fromString("8c850003-0302-41c5-b46e-cf057c562025")
    val COMMAND_CHARACTERISTIC: UUID = UUID.fromString("8c850002-0302-41c5-b46e-cf057c562025")
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun isAnkerLike(name: String?, serviceUuids: List<UUID>): Boolean {
        val n = name.orEmpty().lowercase(Locale.US)
        return n.contains("anker") || n.contains("solix") || n.contains("prime") ||
               n.contains("c200") || n.contains("c300") || n.contains("ugreen") ||
               n.contains("nexode") || n.contains("pb72") || n.contains("pb54") ||
               IDENTIFIER_SERVICE in serviceUuids ||
               com.ben.ankerbattery.protocol.ugreen.UgreenProtocol.isUgreenLike(name, serviceUuids)
    }

    fun classifyDevice(name: String?): DeviceType {
        val n = name.orEmpty().trim().lowercase(Locale.US)
        return when {
            n.contains("165w") || n.contains("pb726") || (n.contains("ugreen") && (n.contains("retractable") || n.contains("20000") || n.contains("20k"))) -> DeviceType.UGREEN_NEXODE_165W
            n.contains("ugreen") || n.contains("nexode") || n.contains("pb72") || n.contains("pb54") -> DeviceType.UGREEN_GENERIC
            n.contains("27k") || n.contains("a1379") || n.contains("27650") -> DeviceType.PRIME_27K
            n.contains("26k") || n.contains("a1341") || (n.contains("a1340") && n.contains("26")) -> DeviceType.PRIME_26K
            n.contains("20k") || n.contains("a1336") || n.contains("prime") || n.contains("power bank") || n.contains("a110") || n.contains("a1340") || n.startsWith("aj") -> DeviceType.PRIME_20K
            n.contains("solix") || n.contains("c200") || n.contains("c300") || n.contains("a172") || n.contains("a176") || n.startsWith("az") || n.startsWith("afy") -> DeviceType.SOLIX_C200
            else -> DeviceType.UNKNOWN
        }
    }

    fun modelNameFor(type: DeviceType): String {
        return when (type) {
            DeviceType.UGREEN_NEXODE_165W -> "UGREEN Nexode 20000mAh 165W"
            DeviceType.UGREEN_GENERIC -> "UGREEN Power Bank"
            DeviceType.PRIME_20K -> "20K Prime Power Bank"
            DeviceType.PRIME_26K -> "26K Prime Power Bank"
            DeviceType.PRIME_27K -> "27K Prime Power Bank"
            DeviceType.SOLIX_C200 -> "SOLIX C200(X)"
            DeviceType.UNKNOWN -> "Battery Manager"
        }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Best-effort parser for unencrypted FF09 framed packets.
     * Newer devices may encrypt model-specific telemetry. In that case the packet
     * is still captured and logged so a decoder can be added without changing BLE code.
     */
    fun parseNotification(current: BatteryTelemetry, raw: ByteArray): BatteryTelemetry {
        val hex = toHex(raw)
        var battery = current.batteryPercent
        var temp = current.temperatureC
        var input = current.totalInputW
        var output = current.totalOutputW

        if (raw.size >= 10 && raw[0] == 0xff.toByte() && raw[1] == 0x09.toByte()) {
            val payloadStart = 9
            val payloadEnd = raw.size - 1
            if (payloadEnd > payloadStart) {
                val payload = raw.copyOfRange(payloadStart, payloadEnd)
                val tlv = parseSimpleTlv(payload)

                tlv[0xA1]?.let { v ->
                    if (v.size >= 2) {
                        val candidate = v.last().toInt() and 0xff
                        if (candidate in 0..100) battery = candidate.toDouble()
                    }
                }
            }
        }

        return current.copy(
            batteryPercent = battery,
            temperatureC = temp,
            totalInputW = input,
            totalOutputW = output,
            lastPacketHex = hex,
            packetsReceived = current.packetsReceived + 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    private fun parseSimpleTlv(payload: ByteArray): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        var i = 0
        if (payload.isNotEmpty() && payload[0] == 0.toByte()) i = 1
        while (i + 1 < payload.size) {
            val tag = payload[i].toInt() and 0xff
            val len = payload[i + 1].toInt() and 0xff
            val start = i + 2
            val end = start + len
            if (len == 0 || end > payload.size) break
            result[tag] = payload.copyOfRange(start, end)
            i = end
        }
        return result
    }
}
