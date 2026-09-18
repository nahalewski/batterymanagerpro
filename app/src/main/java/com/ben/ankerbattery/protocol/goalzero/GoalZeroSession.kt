package com.ben.ankerbattery.protocol.goalzero

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import com.ben.ankerbattery.protocol.BatterySession
import com.ben.ankerbattery.protocol.SessionResult

class GoalZeroSession(override val type: AnkerProtocol.DeviceType) : BatterySession {
    private var negotiated = true

    override fun isNegotiated(): Boolean = negotiated

    override fun initialPacket(): ByteArray? = GoalZeroProtocol.buildStatusQueryPacket()

    override fun queryStatusPacket(): ByteArray? = GoalZeroProtocol.buildStatusQueryPacket()

    override fun onNotification(value: ByteArray, current: BatteryTelemetry): SessionResult {
        val updated = GoalZeroProtocol.parseResponse(value, current)
        return SessionResult(
            telemetry = updated,
            status = "Live telemetry • Goal Zero Yeti"
        )
    }
}
