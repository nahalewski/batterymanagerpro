package com.ben.ankerbattery.protocol.bluetti

import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import com.ben.ankerbattery.protocol.BatterySession
import com.ben.ankerbattery.protocol.SessionResult

class BluettiSession(override val type: AnkerProtocol.DeviceType) : BatterySession {
    private var negotiated = true

    override fun isNegotiated(): Boolean = negotiated

    override fun initialPacket(): ByteArray? = BluettiProtocol.buildReadRegistersPacket()

    override fun queryStatusPacket(): ByteArray? = BluettiProtocol.buildReadRegistersPacket()

    override fun onNotification(value: ByteArray, current: BatteryTelemetry): SessionResult {
        val updated = BluettiProtocol.parseResponse(value, current)
        return SessionResult(
            telemetry = updated,
            status = "Live telemetry • BLUETTI"
        )
    }
}
