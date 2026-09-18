package com.ben.ankerbattery.protocol

import android.util.Log
import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.model.PortReading
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

typealias Result = SessionResult

class AnkerSession(override val type: AnkerProtocol.DeviceType) : BatterySession {

    private data class Packet(val pattern: ByteArray, val cmd: ByteArray, val payload: ByteArray)
    private data class Param(val type: Int?, val value: ByteArray) {
        fun legacy(): ByteArray = if (type == null) value else byteArrayOf(type.toByte()) + value
    }

    private data class PrimePort(val status: Int, val reading: PortReading)

    /** Tracks which encryption/handshake the device actually uses on-the-wire.
     *  Anker Prime 20K/26K/27K devices physically use SOLIX-style BLE handshake
     *  (cmds 0801/0803/0829/0805/0821/4822), so we detect at runtime instead of
     *  assuming based on device classification. */
    private enum class NegotiationMode { UNKNOWN, SOLIX, PRIME }
    private var negotiationMode = NegotiationMode.UNKNOWN

    private var sharedSecret: ByteArray? = null
    private var negotiated = false
    private var mtu = 253
    /** ATT MTU negotiated on the link (default before onMtuChanged fires). */
    private var linkMtu = 23
    private val fragmentBuffers = linkedMapOf<String, MutableList<Pair<Int, ByteArray>>>()

    override fun isNegotiated(): Boolean = negotiated

    override fun onMtuChanged(mtu: Int) {
        linkMtu = mtu
    }

    /** A device only fragments when a packet doesn't fit one notification, so every
     *  non-final fragment arrives at (near) full size. Smaller packets are never fragments —
     *  their first payload byte is ciphertext and must not be read as a fragment header. */
    private fun couldBeFragment(rawSize: Int): Boolean = rawSize >= minOf(linkMtu, mtu) - 8

    /** Prime devices using SOLIX negotiation require periodic status queries.
     *  Pure-Prime-protocol devices are subscription-push-based (no poll needed). */
    override fun isPollingRequired(): Boolean =
        type.isPrime && negotiationMode == NegotiationMode.SOLIX

    override fun initialPacket(): ByteArray {
        return if (type.isPrime) {
            buildPacket("030001", "4001", encodeParams(listOf(param("a1", timestamp()))), CryptoMode.PRIME)
        } else {
            buildPacket(
                "030001", "0001",
                encodeParams(listOf(param("a1", timestamp()), param("a2", SOLIX_UUID.toByteArray()))),
                CryptoMode.SOLIX
            )
        }
    }

    /**
     * Periodic status request for SOLIX power stations. The C200 answers cmd 4200 with a
     * full status dump (4a00) and then streams port readings (4303) for ~10 s, so this is
     * re-sent by the poll loop to keep the stream alive.
     */
    override fun queryStatusPacket(): ByteArray {
        return buildPacket(
            "03000f",
            "4200",
            encodeParams(listOf(param("a1", hex("21")), param("fe", timestamp()))),
            CryptoMode.SOLIX
        )
    }

    /** Older 4040 status query used by AC-model layouts (C300/C800/C1000); the C200 ignores it. */
    private fun legacyQueryStatusPacket(): ByteArray {
        return buildPacket(
            "03000f",
            "4040",
            encodeParams(listOf(param("a1", hex("21")), param("fe", timestamp(), 3))),
            CryptoMode.SOLIX
        )
    }

    /**
     * Subscribe to the main telemetry stream (cmd 4100). Newer-generation firmware
     * (C1000 Gen 2, this C200) only accepts the bare payload a1=0x21 — no timestamp.
     */
    fun subscribeSolixStreamPacket(): ByteArray =
        buildPacket("03000f", "4100", param("a1", hex("21")), CryptoMode.SOLIX)

    /** Subscribe-and-keep-alive (cmd 420b) used by Prime chargers; same bare payload. */
    private fun keepAlivePacket(): ByteArray =
        buildPacket("03000f", "420b", param("a1", hex("21")), CryptoMode.SOLIX)

    override fun pollPackets(): List<ByteArray> =
        if (type.isPrime) listOfNotNull(queryStatusPacket())
        else listOf(queryStatusPacket(), subscribeSolixStreamPacket(), keepAlivePacket())

    override fun onNotification(raw: ByteArray, current: BatteryTelemetry): Result {
        val packet = parsePacket(raw) ?: run {
            Log.d("AnkerBle", "Malformed/partial BLE packet rawLen=${raw.size} raw=${raw.toHex()}")
            return Result(status = "Ignored malformed BLE packet")
        }
        val pattern = packet.pattern.toHex()
        val cmd = packet.cmd.toHex()
        var payload = packet.payload

        Log.d("AnkerBle", "RX packet pattern=$pattern cmd=$cmd rawLen=${raw.size} payloadLen=${payload.size} payload=${payload.toHex()}")

        val hasFrag = payload.isNotEmpty() && couldBeFragment(raw.size) && run {
            val frag = payload[0].toInt() and 0xff
            val index = (frag ushr 4) and 0x0f
            val total = frag and 0x0f
            total in 2..15 && index <= total
        }

        if (hasFrag || fragmentBuffers.containsKey(pattern + cmd)) {
            val complete = reassemble(pattern + cmd, payload)
            if (complete == null) {
                Log.d("AnkerBle", "Buffering fragment for $pattern/$cmd...")
                return Result(status = "Buffering fragment…")
            }
            payload = complete
            Log.d("AnkerBle", "Reassembled complete payload for $pattern/$cmd (${payload.size} bytes)")
        }

        return try {
            when (pattern) {
                "030001" -> processNegotiation(cmd, payload)
                "03010f", "030111", "03000f" -> processSessionPacket(cmd, payload, current)
                else -> {
                    Log.d("AnkerBle", "Unhandled Anker packet pattern $pattern/$cmd")
                    Result(status = "Received Anker packet $pattern/$cmd")
                }
            }
        } catch (t: Throwable) {
            Log.e("AnkerBle", "Telemetry decode error for $pattern/$cmd", t)
            Result(status = "Telemetry decode error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun processNegotiation(cmd: String, encryptedOrPlain: ByteArray): Result {
        // Detect which handshake the device is actually using on the wire.
        // Anker Prime devices sometimes respond with SOLIX-style cmds (0801/0821/4822),
        // so we must route by the first received cmd rather than the device classification.
        if (negotiationMode == NegotiationMode.UNKNOWN) {
            negotiationMode = when {
                cmd.startsWith("08") || cmd == "4822" -> {
                    Log.d("AnkerBle", "Detected SOLIX-style negotiation (cmd=$cmd) for ${type.name}")
                    NegotiationMode.SOLIX
                }
                cmd.startsWith("48") -> {
                    Log.d("AnkerBle", "Detected PRIME-style negotiation (cmd=$cmd) for ${type.name}")
                    NegotiationMode.PRIME
                }
                else -> NegotiationMode.SOLIX // fallback
            }
        }
        return when (negotiationMode) {
            NegotiationMode.PRIME -> processPrimeNegotiation(cmd, encryptedOrPlain)
            else -> processSolixNegotiation(cmd, encryptedOrPlain)
        }
    }

    private fun processSolixNegotiation(cmd: String, payload: ByteArray): Result {
        val plain = decryptSolix(payload)
        val params = parseParams(plain)
        val packets = mutableListOf<ByteArray>()
        Log.d("AnkerBle", "SOLIX negotiation stage cmd=$cmd plainLen=${plain.size} params=${params.keys}")

        when (cmd) {
            "0801" -> packets += sendSolix("0003", listOf(
                param("a1", timestamp()), param("a2", SOLIX_UUID.toByteArray()),
                param("a3", hex("20")), param("a4", hex("00f0"))))
            "0803" -> {
                params.intVal("a2")?.takeIf { it in 23..517 }?.let { mtu = it }
                packets += sendSolix("0029", listOf(param("a1", timestamp()), param("a2", SOLIX_UUID.toByteArray())))
            }
            "0829" -> {
                // Identity stage: a2 = chip ("ESP32"), a3 = firmware version, a4 = serial, a5 = MAC.
                val firmware = params.asciiVal("a3")
                Log.d("AnkerBle", "SOLIX identity: chip=${params.asciiVal("a2")} fw=$firmware serial=${params.asciiVal("a4")}")
                packets += sendSolix("0005", listOf(
                    param("a1", timestamp()), param("a2", SOLIX_UUID.toByteArray()),
                    param("a3", hex("20")), param("a4", hex("00f0")), param("a5", hex("40"))))
                return Result(packets, status = "Negotiating SOLIX session…", firmwareVersion = firmware)
            }
            "0805" -> packets += sendSolix("0021", listOf(param("a1", hex(SOLIX_PUBLIC_KEY_BLOB))))
            "0821" -> {
                val remote = params["a1"]?.legacy() ?: return Result(status = "SOLIX negotiation public key missing")
                sharedSecret = deriveSharedSecret(SOLIX_PRIVATE_KEY, remote)
                Log.d("AnkerBle", "Derived SOLIX shared secret successfully (device=${type.name})")
                // Stage 5: switch to the encrypted session. The device acks with 4822 and
                // ignores session traffic sent before the 4027/4827 confirm, so wait for it.
                packets += sendSolix("4022", listOf(
                    param("a1", timestamp()), param("a2", SOLIX_UUID.toByteArray()),
                    param("a3", hex("20")), param("a4", hex("00000000")), param("a5", POSIX_TZ.toByteArray())))
                return Result(packets, status = "Securing SOLIX telemetry session…")
            }
            "4822" -> packets += sendSolix("4027", listOf(param("a1", timestamp()), param("a2", SOLIX_UUID.toByteArray())))
            "4827" -> {
                negotiated = true
                packets += buildPacket("03000f", "4200", encodeParams(listOf(
                    param("a1", hex("21")), param("fe", timestamp()))), CryptoMode.SOLIX)
                packets += buildPacket("03000f", "420a", encodeParams(listOf(
                    param("a1", hex("21")), param("a2", hex("044742")),
                    param("a3", SOLIX_UUID.toByteArray(), 4), param("a5", hex("0101")),
                    param("fe", timestamp()))), CryptoMode.SOLIX)
                // Legacy AC-model queries (ignored by the C200; kept for C300/C800/C1000 layouts).
                packets += subscribeSolixStreamPacket()
                packets += keepAlivePacket()
                packets += legacyQueryStatusPacket()
                return Result(packets, status = "Encrypted SOLIX session established — querying status…")
            }
            else -> return Result(status = "SOLIX negotiation response $cmd")
        }
        return Result(packets, status = "Negotiating SOLIX session…")
    }

    private fun processPrimeNegotiation(cmd: String, payload: ByteArray): Result {
        val plain = decryptPrime(payload)
        val params = parseParams(plain)
        val packets = mutableListOf<ByteArray>()
        Log.d("AnkerBle", "Prime negotiation stage cmd=$cmd plain=${plain.toHex()} params=${params.keys}")
        when (cmd) {
            "4801" -> packets += sendPrime("4003", listOf(
                param("a1", timestamp()), param("a3", hex("20")), param("a4", hex("00f0"))))
            "4803" -> {
                params.intVal("a2")?.takeIf { it in 23..517 }?.let { mtu = it }
                packets += sendPrime("4029", listOf(param("a1", timestamp())))
            }
            "4829" -> {
                // Identity stage: a2 = product line ("Charging"), a3 = firmware version, a4 = serial.
                val firmware = params.asciiVal("a3")
                Log.d("AnkerBle", "Prime identity: line=${params.asciiVal("a2")} fw=$firmware serial=${params.asciiVal("a4")}")
                packets += sendPrime("4005", listOf(
                    param("a1", timestamp()), param("a3", hex("20")), param("a4", hex("2901")),
                    param("a5", hex("44")), param("a6", hex("02"))))
                return Result(packets, status = "Negotiating Prime session…", firmwareVersion = firmware)
            }
            "4805" -> packets += sendPrime("4021", listOf(param("a1", hex(PRIME_PUBLIC_KEY_BLOB))))
            "4821" -> {
                val remote = params["a1"]?.legacy() ?: return Result(status = "Prime negotiation public key missing")
                sharedSecret = deriveSharedSecret(PRIME_PRIVATE_KEY, remote)
                packets += sendPrime("4022", listOf(
                    param("a1", timestamp()), param("a3", hex("00000000")), param("a5", POSIX_TZ.toByteArray())))
                return Result(packets, status = "Securing Prime telemetry session…")
            }
            "4822" -> packets += sendPrime("4027", listOf(param("a1", timestamp()), param("a2", PRIME_UUID.toByteArray())))
            "4827" -> {
                negotiated = true
                packets += buildPacket("03000f", "4200", encodeParams(listOf(
                    param("a1", hex("21")), param("fe", timestamp()))), CryptoMode.PRIME)
                packets += buildPacket("03000f", "420a", encodeParams(listOf(
                    param("a1", hex("21")), param("a2", hex("044742")),
                    param("a3", PRIME_UUID.toByteArray(), 4), param("a5", hex("0101")),
                    param("fe", timestamp()))), CryptoMode.PRIME)
                return Result(packets, status = "Prime telemetry subscribed — waiting for live values")
            }
            else -> return Result(status = "Prime negotiation response $cmd")
        }
        return Result(packets, status = "Negotiating Prime session…")
    }

    private fun processSessionPacket(cmd: String, payload: ByteArray, current: BatteryTelemetry): Result {
        val plain = decryptSessionPayload(cmd, payload)
        val params = parseParams(plain)
        Log.d("AnkerBle", "Decrypted session packet cmd=$cmd mode=$negotiationMode plainLen=${plain.size} tags=${params.keys} plain=${plain.toHex()}")

        if (params.isEmpty()) return Result(status = "Live packet received ($cmd) — parsing…")

        val updated = when {
            type.isPrime -> decodePrime20k(params, current, payload)
            // AC-model layout carries battery in b7/bb; everything else uses the record-based layout.
            params.containsKey("b7") || params.containsKey("bb") -> decodeC200(params, current, payload)
            else -> decodeSolixRecords(cmd, params, current, payload)
        }
        return Result(telemetry = updated, status = "Live telemetry • $cmd")
    }

    /**
     * SOLIX C200 (DC) layout, captured from the device:
     *  - 4a00 (reply to 4200): a2 pack voltage, a6 battery %, a7..aa port records, ab..af input records.
     *  - 4303 (1 Hz stream):  a2..a5 the same four port records, a6 u16.
     * Port records share the Prime format (status, volts, amps, watts in 0.1 units). The two
     * long records carry PD fields, so they are taken as the USB-C ports.
     */
    private fun decodeSolixRecords(cmd: String, p: Map<String, Param>, current: BatteryTelemetry, rawPayload: ByteArray): BatteryTelemetry {
        val ports = when (cmd) {
            "4a00" -> listOfNotNull(portRecord(p, "a7", "C1"), portRecord(p, "a8", "C2"), portRecord(p, "a9", "USB-A"), portRecord(p, "aa", "DC OUT"))
            "4303" -> listOfNotNull(portRecord(p, "a2", "C1"), portRecord(p, "a3", "C2"), portRecord(p, "a4", "USB-A"), portRecord(p, "a5", "DC OUT"))
            else -> emptyList()
        }
        // Battery % is not carried in either packet on this firmware (a6 is a flag, not SoC —
        // the device read 100% while a6 == 1). Leave it unknown rather than show a wrong value.
        val battery: Double? = null
        val totalIn = ports.filter { it.status == 2 }.sumOf { it.reading.watts ?: 0.0 }
        val totalOut = ports.filter { it.status == 1 }.sumOf { it.reading.watts ?: 0.0 }

        Log.d("AnkerBle", "Parsed SOLIX $cmd: batt=${battery ?: current.batteryPercent}% ports=${ports.map { "${it.reading.name}:${it.reading.watts}W@${it.reading.volts}V" }}")

        return current.copy(
            batteryPercent = battery ?: current.batteryPercent,
            totalInputW = if (ports.isEmpty()) current.totalInputW else totalIn,
            totalOutputW = if (ports.isEmpty()) current.totalOutputW else totalOut,
            ports = if (ports.isEmpty()) current.ports else ports.map { it.reading },
            lastPacketHex = rawPayload.toHex(),
            packetsReceived = current.packetsReceived + 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    private fun decryptSessionPayload(cmd: String, payload: ByteArray): ByteArray {
        // The device pushes the 0300 status stream and the 0a00 capability dump in the clear.
        if (cmd == "0300" || cmd == "0a00") return payload
        // Use the actual negotiation mode for decryption — a Prime device that went through
        // SOLIX handshake (0801/0821/4822) shares a SOLIX secret, not a Prime GCM secret.
        return try {
            when {
                negotiationMode == NegotiationMode.PRIME -> decryptPrime(payload)
                negotiationMode == NegotiationMode.SOLIX -> decryptSolix(payload)
                type.isPrime -> decryptPrime(payload)   // fallback if mode not yet set
                else -> decryptSolix(payload)
            }
        } catch (t: Throwable) {
            // Unknown frames may also be plaintext TLV; accept them if they parse, else surface the error.
            if (parseParams(payload).isNotEmpty()) {
                Log.d("AnkerBle", "Session packet $cmd is not encrypted — parsing as plaintext")
                payload
            } else throw t
        }
    }

    private fun decodePrime20k(p: Map<String, Param>, current: BatteryTelemetry, rawPayload: ByteArray): BatteryTelemetry {
        val battery = p.intLegacy("a2", 1, 2)?.takeIf { it in 0..100 }?.toDouble() ?: current.batteryPercent
        val temp = p.signedByteLegacy("af", 1)?.toDouble() ?: current.temperatureC
        val reportedOut = p.intLegacy("a6", 2, 4)?.div(10.0)

        val ports = listOfNotNull(
            portRecord(p, "a8", "C1"),
            portRecord(p, "a9", "C2"),
            portRecord(p, "ac", "USB-A")
        )
        val totalIn = ports.filter { it.status == 2 }.sumOf { it.reading.watts ?: 0.0 }.takeIf { it > 0.0 }
        val summedOut = ports.filter { it.status == 1 }.sumOf { it.reading.watts ?: 0.0 }.takeIf { it > 0.0 }
        val output = reportedOut?.takeIf { it >= 0.0 } ?: summedOut ?: current.totalOutputW

        return current.copy(
            batteryPercent = battery,
            temperatureC = temp,
            totalInputW = totalIn ?: current.totalInputW,
            totalOutputW = output,
            ports = ports.map { it.reading },
            lastPacketHex = rawPayload.toHex(),
            packetsReceived = current.packetsReceived + 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    private fun portRecord(p: Map<String, Param>, tag: String, name: String): PrimePort? {
        val legacy = p[tag]?.legacy() ?: return null
        if (legacy.size < 8) return null
        val status = legacy[1].toInt() and 0xff
        val volts = leInt(legacy.copyOfRange(2, 4)) / 10.0
        val amps = leInt(legacy.copyOfRange(4, 6)) / 10.0
        val watts = leInt(legacy.copyOfRange(6, 8)) / 10.0
        return PrimePort(status, PortReading(name, if (status == 0) 0.0 else watts, volts, amps))
    }

    private fun decodeC200(p: Map<String, Param>, current: BatteryTelemetry, rawPayload: ByteArray): BatteryTelemetry {
        // Battery percentage: tag "b7" on DC models (C200, C200X, C300DC), "bb" on AC models (C300, C800, C1000)
        val battery = (p.intVal("b7") ?: p.intVal("bb"))
            ?.takeIf { it in 0..100 }?.toDouble() ?: current.batteryPercent

        // Temperature: tag "b5" on DC models, "b9" on AC models
        val temp = (p.signedByteVal("b5") ?: p.signedByteVal("b9"))
            ?.toDouble() ?: current.temperatureC

        // Detect DC vs AC model architecture
        val isDcModel = p.containsKey("b7") || p.containsKey("b5") || (!p.containsKey("bb") && p.containsKey("ac"))

        val input = if (isDcModel) {
            p.intVal("ac")?.toDouble() ?: p.intVal("ad")?.toDouble()
        } else {
            p.intVal("ad")?.toDouble() ?: p.intVal("ac")?.toDouble()
        } ?: current.totalInputW

        val output = if (isDcModel) {
            p.intVal("ad")?.toDouble() ?: p.intVal("ae")?.toDouble()
        } else {
            p.intVal("ae")?.toDouble() ?: p.intVal("ad")?.toDouble()
        } ?: current.totalOutputW

        // Remaining time: a3 on DC, a4 on AC (in tenths of an hour, so tenths * 6 = minutes)
        val remainingTenthsHours = if (isDcModel) (p.intVal("a3") ?: p.intVal("a4")) else (p.intVal("a4") ?: p.intVal("a3"))
        val remainingMinutes = remainingTenthsHours?.let { (it * 6.0).toInt() } ?: current.remainingMinutes

        val readings = mutableListOf<PortReading>()
        fun addPort(tag: String, name: String) {
            p.intVal(tag)?.let { w ->
                if (w in 0..10000) readings += PortReading(name, w.toDouble())
            }
        }

        if (isDcModel) {
            // C200, C200X, C300DC port mapping
            addPort("a4", "C1")
            addPort("a5", "C2")
            addPort("a6", "C3")
            addPort("a7", "C4")
            addPort("a8", "USB-A1")
            addPort("a9", "USB-A2")
            addPort("aa", "12V DC")
            addPort("ab", "SOLAR IN")
        } else {
            // AC models (C300, C800, C1000)
            addPort("a5", "AC IN")
            addPort("a6", "AC OUT")
            addPort("a7", "C1")
            addPort("a8", "C2")
            addPort("a9", "C3")
            addPort("aa", "USB-A")
            addPort("ab", "12V DC")
            addPort("ac", "SOLAR IN")
        }

        Log.d("AnkerBle", "Parsed SOLIX telemetry: batt=$battery% temp=$temp°C in=${input}W out=${output}W ports=${readings.map { "${it.name}:${it.watts}W" }}")

        return current.copy(
            batteryPercent = battery,
            temperatureC = temp,
            totalInputW = input,
            totalOutputW = output,
            remainingMinutes = remainingMinutes,
            ports = readings,
            lastPacketHex = rawPayload.toHex(),
            packetsReceived = current.packetsReceived + 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    private enum class CryptoMode { SOLIX, PRIME }

    private fun sendSolix(cmd: String, params: List<ByteArray>): ByteArray =
        buildPacket("030001", cmd, encodeParams(params), CryptoMode.SOLIX)

    private fun sendPrime(cmd: String, params: List<ByteArray>): ByteArray =
        buildPacket("030001", cmd, encodeParams(params), CryptoMode.PRIME)

    private fun buildPacket(pattern: String, cmd: String, plainPayload: ByteArray, mode: CryptoMode): ByteArray {
        val payload = when (mode) {
            CryptoMode.SOLIX -> encryptSolix(plainPayload)
            CryptoMode.PRIME -> encryptPrime(plainPayload)
        }
        val patternBytes = hex(pattern)
        val cmdBytes = hex(cmd)
        val totalLength = 10 + payload.size
        val out = ByteArray(totalLength)
        out[0] = 0xff.toByte(); out[1] = 0x09
        out[2] = (totalLength and 0xff).toByte(); out[3] = ((totalLength ushr 8) and 0xff).toByte()
        patternBytes.copyInto(out, 4)
        cmdBytes.copyInto(out, 7)
        payload.copyInto(out, 9)
        var checksum = 0
        for (i in 0 until out.lastIndex) checksum = checksum xor (out[i].toInt() and 0xff)
        out[out.lastIndex] = checksum.toByte()
        return out
    }

    private fun parsePacket(raw: ByteArray): Packet? {
        if (raw.size < 10 || raw[0] != 0xff.toByte() || raw[1] != 0x09.toByte()) return null
        val declared = (raw[2].toInt() and 0xff) or ((raw[3].toInt() and 0xff) shl 8)
        if (declared != raw.size) return null
        var checksum = 0
        for (i in 0 until raw.lastIndex) checksum = checksum xor (raw[i].toInt() and 0xff)
        if ((checksum and 0xff) != (raw.last().toInt() and 0xff)) return null
        return Packet(raw.copyOfRange(4, 7), raw.copyOfRange(7, 9), raw.copyOfRange(9, raw.lastIndex))
    }

    private fun reassemble(key: String, payload: ByteArray): ByteArray? {
        if (payload.isEmpty()) return payload
        val frag = payload[0].toInt() and 0xff
        val index = (frag ushr 4) and 0x0f
        val total = frag and 0x0f
        if (total <= 1 || total > 15) return payload

        val data = payload.copyOfRange(1, payload.size)
        val list = fragmentBuffers.getOrPut(key) { mutableListOf() }

        val expected = if (list.isEmpty()) {
            if (index == 0 || index == 1) index else -1
        } else {
            list.last().first + 1
        }

        if (index != expected) {
            list.clear()
            if (index == 0 || index == 1) {
                list += index to data
            } else {
                return null
            }
        } else {
            list += index to data
        }

        if (list.size < total) return null

        val output = list.sortedBy { it.first }.fold(ByteArray(0)) { acc, part -> acc + part.second }
        list.clear()
        fragmentBuffers.remove(key)
        return output
    }

    private fun encodeParams(params: List<ByteArray>): ByteArray = params.fold(ByteArray(0)) { a, b -> a + b }
    private fun param(key: String, value: ByteArray, type: Int? = null): ByteArray {
        val body = if (type == null) value else byteArrayOf(type.toByte()) + value
        return hex(key) + byteArrayOf(body.size.toByte()) + body
    }

    private fun parseParams(payload: ByteArray): Map<String, Param> {
        val out = linkedMapOf<String, Param>()
        var i = if (payload.isNotEmpty() && payload[0] == 0.toByte()) 1 else 0
        while (i + 1 < payload.size) {
            val key = "%02x".format(payload[i].toInt() and 0xff)
            val len = payload[i + 1].toInt() and 0xff
            if (i + 2 + len > payload.size) break
            if (len == 0) {
                out[key] = Param(null, ByteArray(0))
                i += 2
                continue
            }
            val body = payload.copyOfRange(i + 2, i + 2 + len)
            if (len > 1) {
                out[key] = Param(body[0].toInt() and 0xff, body.copyOfRange(1, body.size))
            } else {
                out[key] = Param(null, body)
            }
            i += 2 + len
        }
        return out
    }

    private fun Map<String, Param>.intVal(tag: String): Int? {
        val p = this[tag] ?: return null
        val b = p.value
        if (b.isEmpty()) {
            return p.type
        }
        return leInt(b)
    }

    private fun Map<String, Param>.signedByteVal(tag: String): Int? {
        val p = this[tag] ?: return null
        val b = p.value
        if (b.isNotEmpty()) return b[0].toInt()
        return p.type?.toByte()?.toInt()
    }

    private fun Map<String, Param>.asciiVal(tag: String): String? =
        this[tag]?.legacy()?.toString(Charsets.US_ASCII)
            ?.filter { it.code in 32..126 }?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Param>.intLegacy(tag: String, begin: Int, end: Int? = null): Int? {
        val b = this[tag]?.legacy() ?: return null
        if (begin >= b.size) return null
        val e = (end ?: b.size).coerceAtMost(b.size)
        if (e <= begin) return null
        return leInt(b.copyOfRange(begin, e))
    }

    private fun Map<String, Param>.signedByteLegacy(tag: String, index: Int): Int? {
        val b = this[tag]?.legacy() ?: return null
        if (index !in b.indices) return null
        return b[index].toInt()
    }

    private fun encryptSolix(plain: ByteArray): ByteArray {
        val secret = sharedSecret ?: return plain
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret.copyOfRange(0, 16), "AES"), IvParameterSpec(secret.copyOfRange(16, 32)))
            cipher.doFinal(plain)
        } catch (t: Throwable) {
            Log.e("AnkerBle", "encryptSolix failed", t)
            plain
        }
    }

    private fun decryptSolix(payload: ByteArray): ByteArray {
        val secret = sharedSecret ?: return payload
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret.copyOfRange(0, 16), "AES"), IvParameterSpec(secret.copyOfRange(16, 32)))
            cipher.doFinal(payload)
        } catch (t: Throwable) {
            Log.e("AnkerBle", "decryptSolix failed", t)
            payload
        }
    }

    private fun encryptPrime(plain: ByteArray): ByteArray {
        val secret = sharedSecret
        val key = secret?.copyOfRange(0, 16) ?: hex(PRIME_STATIC_KEY)
        val nonce = secret?.copyOfRange(16, 28) ?: hex(PRIME_STATIC_NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(hex(PRIME_AAD))
        return cipher.doFinal(plain)
    }

    private fun decryptPrime(payload: ByteArray): ByteArray {
        val secret = sharedSecret
        val key = secret?.copyOfRange(0, 16) ?: hex(PRIME_STATIC_KEY)
        val nonce = secret?.copyOfRange(16, 28) ?: hex(PRIME_STATIC_NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(hex(PRIME_AAD))
        return cipher.doFinal(payload)
    }

    private fun deriveSharedSecret(privateHex: String, remoteXY: ByteArray): ByteArray {
        require(remoteXY.size >= 64) { "Invalid P-256 public key (${remoteXY.size} bytes)" }
        val xy = if (remoteXY.size == 65 && remoteXY[0] == 4.toByte()) remoteXY.copyOfRange(1, 65) else remoteXY.copyOfRange(remoteXY.size - 64, remoteXY.size)
        val x = BigInteger(1, xy.copyOfRange(0, 32))
        val y = BigInteger(1, xy.copyOfRange(32, 64))
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        val ecSpec = parameters.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(ECPrivateKeySpec(BigInteger(privateHex, 16), ecSpec))
        val publicKey = keyFactory.generatePublic(ECPublicKeySpec(ECPoint(x, y), ecSpec))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun timestamp(): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putInt((System.currentTimeMillis() / 1000L).toInt()).array()

    private fun leInt(bytes: ByteArray): Int {
        var result = 0
        for (i in bytes.indices.reversed()) result = (result shl 8) or (bytes[i].toInt() and 0xff)
        return result
    }

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val POSIX_TZ = "EST5EDT,M3.2.0,M11.1.0"
        private const val SOLIX_UUID = "b2dc0b17-b75d-4abf-ba6e-ec7c997c23e7"
        private const val PRIME_UUID = "79ebed35-dc9c-4904-b40c-72c4e863aa10"
        private const val SOLIX_PRIVATE_KEY = "7dfbea61cd95cee49c458ad7419e817f1ade9a66136de3c7d5787af1458e39f4"
        private const val PRIME_PRIVATE_KEY = "754744d72984c378bc4fa77d7fcdf6bbb6d9df119fa9be4948eb8a3b4cd6071f"
        private const val SOLIX_PUBLIC_KEY_BLOB = "060ea168f232aedb37fb2d120c49180329ac72ab5ec3eb8fd30a2f252dc5e151dabccd9b1dc1e288704ca760a0d8c918e5c94823a1f609a4bf07fb4c33ee2190"
        private const val PRIME_PUBLIC_KEY_BLOB = "d5e3020a220079c96517fd47d6023df4f5530914cc6843aaad76cf888537c4cd7db4c879056ea7d5ff83696f0f32bd7034b251396bf0b1bb1f37a7446857d1a6"
        private const val PRIME_STATIC_KEY = "b8ff7422955d4eb6d554a2c470280559"
        private const val PRIME_STATIC_NONCE = "6ba3e3f2f3a60f2971ce5d1f"
        private const val PRIME_AAD = "3322110077665544bbaa9988ffeeddcc"
    }
}
