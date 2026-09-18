package com.ben.ankerbattery.model

data class PortReading(
    val name: String,
    val watts: Double? = null,
    val volts: Double? = null,
    val amps: Double? = null
)

data class BatteryTelemetry(
    val deviceName: String = "Battery device",
    val modelName: String = "Battery",
    val address: String = "",
    val connected: Boolean = false,
    val batteryPercent: Double? = null,
    val temperatureC: Double? = null,
    val totalInputW: Double? = null,
    val totalOutputW: Double? = null,
    val remainingMinutes: Int? = null,
    val ports: List<PortReading> = emptyList(),
    val lastPacketHex: String = "",
    val packetsReceived: Long = 0,
    val lastUpdatedMs: Long = 0,
    val firmwareVersion: String? = null,
    val updateAvailable: Boolean = false,
    val officialAppName: String? = null,
    val officialAppPackage: String? = null
)
