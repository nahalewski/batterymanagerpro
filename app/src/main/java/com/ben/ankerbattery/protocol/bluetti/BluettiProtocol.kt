package com.ben.ankerbattery.protocol.bluetti

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.model.PortReading
import java.util.Locale
import java.util.UUID

/**
 * BLUETTI Open SDK / Modbus-over-BLE Protocol Layer.
 * Supports EB3A, EB55, EB70, AC60, AC180, AC200MAX, AC300, AC500, EP500 series.
 */
object BluettiProtocol {
    val BLUETTI_SERVICE: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun isBluetti(name: String?, serviceUuids: List<UUID>): Boolean {
        val n = name.orEmpty().lowercase(Locale.US)
        return n.contains("bluetti") || n.contains("eb3a") || n.contains("eb55") ||
               n.contains("eb70") || n.contains("ac60") || n.contains("ac180") ||
               n.contains("ac200") || n.contains("ac300") || n.contains("ac500") ||
               n.contains("ep500") || BLUETTI_SERVICE in serviceUuids
    }

    /**
     * Builds Modbus Read Holding Registers command (Function 0x03).
     * Device address 0x01, Function 0x03, Start reg 0x000A, Count 0x0020, CRC16.
     */
    fun buildReadRegistersPacket(startReg: Int = 0x000A, count: Int = 0x0020): ByteArray {
        val buf = byteArrayOf(
            0x01.toByte(),
            0x03.toByte(),
            ((startReg shr 8) and 0xFF).toByte(),
            (startReg and 0xFF).toByte(),
            ((count shr 8) and 0xFF).toByte(),
            (count and 0xFF).toByte()
        )
        val crc = calculateModbusCrc(buf)
        return buf + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    private fun calculateModbusCrc(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if ((crc and 1) != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc
    }

    fun parseResponse(payload: ByteArray, current: BatteryTelemetry): BatteryTelemetry {
        if (payload.size < 5 || payload[1] != 0x03.toByte()) return current
        val byteCount = payload[2].toInt() and 0xFF
        if (payload.size < 3 + byteCount) return current

        fun reg(offset: Int): Int {
            val idx = 3 + offset * 2
            if (idx + 1 >= payload.size) return 0
            return ((payload[idx].toInt() and 0xFF) shl 8) or (payload[idx + 1].toInt() and 0xFF)
        }

        val soc = reg(0).coerceIn(0, 100).toDouble()
        val dcInW = reg(1).toDouble() // Solar / Car DC Input Watts
        val acInW = reg(2).toDouble() // AC Grid Input Watts
        val dcOutW = reg(3).toDouble() // DC Output Watts
        val acOutW = reg(4).toDouble() // AC Output Watts
        val temp = reg(5) / 10.0 // Internal Temperature C

        val totalIn = dcInW + acInW
        val totalOut = dcOutW + acOutW

        val ports = listOf(
            PortReading("AC Output", watts = acOutW),
            PortReading("DC 12V/USB", watts = dcOutW),
            PortReading("Solar/PV In", watts = dcInW),
            PortReading("AC Grid In", watts = acInW)
        )

        return current.copy(
            batteryPercent = soc,
            totalInputW = totalIn,
            totalOutputW = totalOut,
            temperatureC = if (temp in -20.0..80.0) temp else current.temperatureC,
            ports = ports,
            packetsReceived = current.packetsReceived + 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }
}
