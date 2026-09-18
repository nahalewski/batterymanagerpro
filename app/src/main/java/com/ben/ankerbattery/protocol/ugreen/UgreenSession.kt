package com.ben.ankerbattery.protocol.ugreen

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import com.ben.ankerbattery.protocol.BatterySession
import com.ben.ankerbattery.protocol.SessionResult

class UgreenSession(override val type: AnkerProtocol.DeviceType) : BatterySession {
    private var negotiated = true

    override fun isNegotiated(): Boolean = negotiated

    override fun initialPacket(): ByteArray? {
        return UgreenProtocol.buildStatusQueryPacket()
    }

    override fun queryStatusPacket(): ByteArray? {
        return UgreenProtocol.buildStatusQueryPacket()
    }

    override fun onNotification(value: ByteArray, current: BatteryTelemetry): SessionResult {
        // If 1-byte standard BLE battery notification
        if (value.size == 1) {
            val updated = UgreenProtocol.parseBatteryLevel(value, current)
            return SessionResult(
                telemetry = updated,
                status = "Live telemetry • ${updated.batteryPercent?.toInt() ?: 0}%"
            )
        }

        // Parse UGREEN telemetry frame
        val updated = UgreenProtocol.parseUgreenTelemetry(value, current)
        return SessionResult(
            telemetry = updated,
            status = "Live telemetry • UGREEN Nexode"
        )
    }
}
