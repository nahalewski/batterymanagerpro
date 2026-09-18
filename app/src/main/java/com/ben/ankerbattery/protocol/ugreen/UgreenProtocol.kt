package com.ben.ankerbattery.protocol.ugreen

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.model.PortReading
import java.util.Locale
import java.util.UUID

object UgreenProtocol {
    // Standard Bluetooth SIG GATT Services
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val MODEL_NUMBER_CHAR: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")

    // UGREEN Smart Power Bank Custom Services (Nexode / Connect App)
    val UGREEN_CUSTOM_SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val UGREEN_NOTIFY_CHAR: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val UGREEN_COMMAND_CHAR: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

    // Secondary / Alternative UGREEN Smart Charger Service
    val UGREEN_ALT_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val UGREEN_ALT_NOTIFY_CHAR: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun isUgreenLike(name: String?, serviceUuids: List<UUID>): Boolean {
        val n = name.orEmpty().lowercase(Locale.US)
        return n.contains("ugreen") || n.contains("nexode") || n.contains("pb726") ||
               n.contains("pb720") || n.contains("pb722") || n.contains("pb541") ||
               UGREEN_CUSTOM_SERVICE in serviceUuids || UGREEN_ALT_SERVICE in serviceUuids
    }

    /**
     * Builds standard query status packet for Ugreen Nexode.
     * Header 0xAA 0x55, Cmd 0x01 (Status Query), Len 0x00, Checksum
     */
    fun buildStatusQueryPacket(): ByteArray {
        val header = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01.toByte(), 0x00.toByte())
        var sum = 0
        for (b in header) sum += (b.toInt() and 0xFF)
        val checksum = (sum and 0xFF).toByte()
        return header + byteArrayOf(checksum)
    }

    /**
     * Parses standard Battery Level notification (0x2A19: 1 byte, 0..100)
     */
    fun parseBatteryLevel(raw: ByteArray, current: BatteryTelemetry): BatteryTelemetry {
        if (raw.isEmpty()) return current
        val level = (raw[0].toInt() and 0xFF).coerceIn(0, 100).toDouble()
        return current.copy(
            batteryPercent = level,
            packetsReceived = current.packetsReceived + 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    /**
     * Decodes UGREEN proprietary telemetry frame.
     * Supports Nexode 165W 3-port telemetry:
     * Port 1 (C1 Retractable): up to 140W (or 100W)
     * Port 2 (C2 Port): up to 100W
     * Port 3 (USB-A): up to 22.5W
     */
    fun parseUgreenTelemetry(payload: ByteArray, current: BatteryTelemetry): BatteryTelemetry {
        if (payload.size < 4) return current
        var battery = current.batteryPercent
        var totalIn = current.totalInputW
        var totalOut = current.totalOutputW
        var temp = current.temperatureC
        var remainingMin = current.remainingMinutes
        val ports = mutableListOf<PortReading>()

        // Check if standard Ugreen header 0xAA 0x55 or 0x55 0xAA
        if ((payload[0] == 0xAA.toByte() && payload[1] == 0x55.toByte()) ||
            (payload[0] == 0x55.toByte() && payload[1] == 0xAA.toByte())) {
            var i = 2
            while (i + 1 < payload.size - 1) { // Reserve 1 byte for checksum
                val tag = payload[i].toInt() and 0xFF
                val len = payload[i + 1].toInt() and 0xFF
                val start = i + 2
                val end = start + len
                if (len == 0 || end > payload.size) break
                val data = payload.copyOfRange(start, end)

                when (tag) {
                    0x01 -> { // Battery percentage
                        if (data.isNotEmpty()) battery = (data[0].toInt() and 0xFF).coerceIn(0, 100).toDouble()
                    }
                    0x02 -> { // Total Output Watts
                        if (data.size >= 2) {
                            val rawW = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                            totalOut = rawW / 10.0
                        }
                    }
                    0x03 -> { // Total Input Watts
                        if (data.size >= 2) {
                            val rawW = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                            totalIn = rawW / 10.0
                        }
                    }
                    0x04 -> { // Temperature in C
                        if (data.isNotEmpty()) temp = data[0].toDouble()
                    }
                    0x05 -> { // Remaining minutes
                        if (data.size >= 2) {
                            remainingMin = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        }
                    }
                    0x10 -> { // C1 Retractable port
                        if (data.size >= 4) {
                            val v = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)) / 100.0
                            val a = (((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)) / 100.0
                            val w = v * a
                            ports.add(PortReading(name = "C1 (Cable)", watts = w, volts = v, amps = a))
                        }
                    }
                    0x11 -> { // C2 USB-C port
                        if (data.size >= 4) {
                            val v = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)) / 100.0
                            val a = (((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)) / 100.0
                            val w = v * a
                            ports.add(PortReading(name = "C2", watts = w, volts = v, amps = a))
                        }
                    }
                    0x12 -> { // USB-A port
                        if (data.size >= 4) {
                            val v = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)) / 100.0
                            val a = (((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)) / 100.0
                            val w = v * a
                            ports.add(PortReading(name = "USB-A", watts = w, volts = v, amps = a))
                        }
                    }
                }
                i = end
            }
        }

        return current.copy(
            batteryPercent = battery,
            totalInputW = totalIn,
            totalOutputW = totalOut,
            temperatureC = temp,
            remainingMinutes = remainingMin,
            ports = if (ports.isNotEmpty()) ports else current.ports,
            packetsReceived = current.packetsReceived + 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }
}
