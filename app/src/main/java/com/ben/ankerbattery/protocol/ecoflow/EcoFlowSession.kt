package com.ben.ankerbattery.protocol.ecoflow

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import com.ben.ankerbattery.protocol.BatterySession
import com.ben.ankerbattery.protocol.SessionResult

class EcoFlowSession(override val type: AnkerProtocol.DeviceType) : BatterySession {
    private var negotiated = true

    override fun isNegotiated(): Boolean = negotiated

    override fun initialPacket(): ByteArray? = EcoFlowProtocol.buildStatusQueryPacket()

    override fun queryStatusPacket(): ByteArray? = EcoFlowProtocol.buildStatusQueryPacket()

    override fun onNotification(value: ByteArray, current: BatteryTelemetry): SessionResult {
        val updated = EcoFlowProtocol.parseResponse(value, current)
        return SessionResult(
            telemetry = updated,
            status = "Live telemetry • EcoFlow Open API"
        )
    }
}
