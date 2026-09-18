package com.ben.ankerbattery.protocol

import com.ben.ankerbattery.model.BatteryTelemetry

data class SessionResult(
    val packetsToWrite: List<ByteArray> = emptyList(),
    val telemetry: BatteryTelemetry? = null,
    val status: String? = null
)

interface BatterySession {
    val type: AnkerProtocol.DeviceType
    fun isNegotiated(): Boolean
    fun initialPacket(): ByteArray?
    fun queryStatusPacket(): ByteArray?
    fun onNotification(value: ByteArray, current: BatteryTelemetry): SessionResult
}
