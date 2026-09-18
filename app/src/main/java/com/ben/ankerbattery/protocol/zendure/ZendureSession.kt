package com.ben.ankerbattery.protocol.zendure

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import com.ben.ankerbattery.protocol.BatterySession
import com.ben.ankerbattery.protocol.SessionResult

class ZendureSession(override val type: AnkerProtocol.DeviceType) : BatterySession {
    private var negotiated = true

    override fun isNegotiated(): Boolean = negotiated

    override fun initialPacket(): ByteArray? = ZendureProtocol.buildStatusQueryPacket()

    override fun queryStatusPacket(): ByteArray? = ZendureProtocol.buildStatusQueryPacket()

    override fun onNotification(value: ByteArray, current: BatteryTelemetry): SessionResult {
        val updated = ZendureProtocol.parseResponse(value, current)
        return SessionResult(
            telemetry = updated,
            status = "Live telemetry • Zendure ZenSDK"
        )
    }
}
