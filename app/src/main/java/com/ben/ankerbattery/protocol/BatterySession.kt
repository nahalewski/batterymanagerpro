package com.ben.ankerbattery.protocol

import com.ben.ankerbattery.model.BatteryTelemetry

data class SessionResult(
    val packetsToWrite: List<ByteArray> = emptyList(),
    val telemetry: BatteryTelemetry? = null,
    val status: String? = null,
    /** Version string the device reported about itself, when a packet carried one. */
    val firmwareVersion: String? = null
)

interface BatterySession {
    val type: AnkerProtocol.DeviceType
    fun isNegotiated(): Boolean
    /** Returns true if this session needs the manager to periodically send queryStatusPacket().
     *  Default false — subscription-based sessions push data automatically. */
    fun isPollingRequired(): Boolean = false
    /** Called with the ATT MTU negotiated for the link, so sessions can size fragment handling. */
    fun onMtuChanged(mtu: Int) {}
    fun initialPacket(): ByteArray?
    fun queryStatusPacket(): ByteArray?
    /** Packets the manager sends on each poll tick; defaults to the single status query. */
    fun pollPackets(): List<ByteArray> = listOfNotNull(queryStatusPacket())
    fun onNotification(value: ByteArray, current: BatteryTelemetry): SessionResult
}
