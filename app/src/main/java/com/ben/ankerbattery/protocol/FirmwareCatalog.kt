package com.ben.ankerbattery.protocol

/**
 * Newest firmware versions known to this app, per model.
 *
 * Devices report their own version string during the BLE handshake (identity stage
 * 0829/4829, tag a3). An update is flagged only when that reported version is older
 * than the entry here; models without an entry never show an update prompt.
 * Bump these when Anker ships newer firmware.
 */
object FirmwareCatalog {
    private val latestKnown = mapOf(
        AnkerProtocol.DeviceType.PRIME_20K to "v0.0.5.2",
        AnkerProtocol.DeviceType.SOLIX_C200 to "0.0.0.3",
    )

    fun latestFor(type: AnkerProtocol.DeviceType): String? = latestKnown[type]

    fun isUpdateAvailable(type: AnkerProtocol.DeviceType, deviceVersion: String?): Boolean {
        val latest = latestKnown[type] ?: return false
        val current = deviceVersion ?: return false
        return compareVersions(current, latest) < 0
    }

    /** Numeric dotted comparison ("v1.2.10" > "1.2.9"); missing components count as 0. */
    fun compareVersions(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parts(version: String): List<Int> =
        version.trim().trimStart('v', 'V')
            .split('.', '-', '_')
            .mapNotNull { part -> part.filter { it.isDigit() }.toIntOrNull() }
}
