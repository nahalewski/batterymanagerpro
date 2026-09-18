package com.ben.ankerbattery.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import com.ben.ankerbattery.protocol.AnkerSession
import com.ben.ankerbattery.protocol.BatterySession
import com.ben.ankerbattery.protocol.ugreen.UgreenProtocol
import com.ben.ankerbattery.protocol.ugreen.UgreenSession
import com.ben.ankerbattery.protocol.bluetti.BluettiProtocol
import com.ben.ankerbattery.protocol.bluetti.BluettiSession
import com.ben.ankerbattery.protocol.zendure.ZendureProtocol
import com.ben.ankerbattery.protocol.zendure.ZendureSession
import com.ben.ankerbattery.protocol.ecoflow.EcoFlowProtocol
import com.ben.ankerbattery.protocol.ecoflow.EcoFlowSession
import com.ben.ankerbattery.protocol.goalzero.GoalZeroProtocol
import com.ben.ankerbattery.protocol.goalzero.GoalZeroSession
import com.ben.ankerbattery.util.NotificationHelper
import com.ben.ankerbattery.widget.BatteryWidgetProvider
import java.util.ArrayDeque

@SuppressLint("MissingPermission")
class AnkerBleManager(private val context: Context) {
    data class FoundDevice(
        val name: String,
        val modelName: String,
        val type: AnkerProtocol.DeviceType,
        val address: String,
        val rssi: Int,
        val device: BluetoothDevice
    )

    interface Listener {
        fun onDevicesChanged(devices: List<FoundDevice>)
        fun onTelemetryChanged(telemetry: BatteryTelemetry)
        fun onStatusChanged(status: String)
    }

    var listener: Listener? = null

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var deviceList: List<FoundDevice> = emptyList()
    private var currentTelemetry = BatteryTelemetry()
    private var currentStatus = "Idle"
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var connectAttempt = 0
    private var lastFoundDevice: FoundDevice? = null
    private var isConnecting = false
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var session: BatterySession? = null
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            val currentSession = session
            if (gatt != null && currentSession != null && currentSession.isNegotiated()) {
                if (!currentSession.type.isPrime) {
                    if (!writeInProgress && writeQueue.isEmpty()) {
                        currentSession.queryStatusPacket()?.let {
                            Log.d("AnkerBle", "Polling status update...")
                            enqueueWrite(it)
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, 3000L)
        }
    }

    val devices: List<FoundDevice> get() = deviceList
    val telemetry: BatteryTelemetry get() = currentTelemetry
    val status: String get() = currentStatus

    private fun setStatus(value: String) {
        currentStatus = value
        listener?.onStatusChanged(value)
    }

    private fun setTelemetry(value: BatteryTelemetry) {
        currentTelemetry = value
        listener?.onTelemetryChanged(value)
        try {
            BatteryWidgetProvider.updateAllWidgets(context, value)
        } catch (_: Throwable) {}
    }

    private fun setDevices(value: List<FoundDevice>) {
        deviceList = value
        listener?.onDevicesChanged(value)
    }

    fun dispatchCurrentState() {
        listener?.onDevicesChanged(deviceList)
        listener?.onTelemetryChanged(currentTelemetry)
        listener?.onStatusChanged(currentStatus)
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun reportPermissionDenied() {
        setStatus("Bluetooth permission is required to find and connect to batteries.")
    }

    fun startScan() {
        if (!hasPermissions()) {
            reportPermissionDenied()
            return
        }
        val bt = adapter
        if (bt == null) {
            setStatus("Bluetooth is not available on this device.")
            return
        }
        if (!bt.isEnabled) {
            setStatus("Turn on Bluetooth, then scan again.")
            return
        }
        val scanner = bt.bluetoothLeScanner
        if (scanner == null) {
            setStatus("Bluetooth LE scanner is unavailable.")
            return
        }
        if (scanning) scanner.stopScan(scanCallback)
        setDevices(emptyList())
        setStatus("Scanning…")
        scanning = true
        scanner.startScan(scanCallback)
    }

    fun stopScan() {
        if (!hasPermissions() || !scanning) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        if (currentStatus.startsWith("Scanning")) setStatus("Scan stopped")
    }

    fun getOfficialAppInfo(type: AnkerProtocol.DeviceType): Pair<String, String> {
        return when {
            type.isUgreen -> "UGREEN" to "com.ugreen.connect"
            type.isBluetti -> "BLUETTI" to "com.bluetti.app"
            type.isZendure -> "Zendure" to "com.zendure"
            type.isEcoFlow -> "EcoFlow" to "com.ecoflow"
            type.isGoalZero -> "Goal Zero Yeti" to "com.goalzero.yeti"
            type == AnkerProtocol.DeviceType.SOLIX_C200 -> "Anker SOLIX" to "com.anker.powerstation"
            else -> "Anker" to "com.anker.charging"
        }
    }

    fun connect(found: FoundDevice) {
        if (isConnecting) {
            setStatus("Connection already in progress…")
            return
        }
        if (!hasPermissions()) {
            reportPermissionDenied()
            return
        }
        stopScan()
        closeGatt()
        resetSessionState()
        connectAttempt = 1
        isConnecting = true
        lastFoundDevice = found
        session = createSessionFor(found.type)
        setStatus("Connecting to ${found.modelName}…")
        val (appName, appPkg) = getOfficialAppInfo(found.type)
        val initialTelemetry = BatteryTelemetry(
            deviceName = found.name,
            modelName = found.modelName,
            address = found.address,
            firmwareVersion = "v1.2.0",
            updateAvailable = true,
            officialAppName = appName,
            officialAppPackage = appPkg
        )
        setTelemetry(initialTelemetry)
        NotificationHelper.notifyFirmwareUpdate(context, initialTelemetry)
        gatt = connectGattForAttempt(found, connectAttempt)
    }

    fun disconnect() {
        stopScan()
        isConnecting = false
        connectAttempt = 0
        resetSessionState()
        val current = gatt
        gatt = null
        if (current != null) {
            try { current.disconnect() } catch (_: Throwable) {}
            try { current.close() } catch (_: Throwable) {}
        }
        setTelemetry(currentTelemetry.copy(connected = false))
        setStatus("Disconnected")
    }

    private fun resetSessionState() {
        mainHandler.removeCallbacks(pollRunnable)
        telemetryCharacteristic = null
        commandCharacteristic = null
        session = null
        writeQueue.clear()
        writeInProgress = false
    }

    private fun closeGatt(target: BluetoothGatt? = gatt) {
        if (target != null) {
            try { target.close() } catch (_: Throwable) {}
            if (target === gatt) gatt = null
        }
    }

    private fun connectGattForAttempt(found: FoundDevice, attempt: Int): BluetoothGatt {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && attempt == 1 ->
                found.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && attempt == 2 ->
                found.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            else -> found.device.connectGatt(context, false, gattCallback)
        }
    }

    private fun retryConnection(reasonStatus: Int) {
        val found = lastFoundDevice ?: run {
            isConnecting = false
            setStatus("Bluetooth connection error: $reasonStatus")
            mainHandler.postDelayed({
                if (!currentTelemetry.connected && !isConnecting) startScan()
            }, 2500L)
            return
        }
        if (connectAttempt >= 3) {
            isConnecting = false
            setStatus("Bluetooth connection error: $reasonStatus — auto-retrying scan…")
            setTelemetry(currentTelemetry.copy(connected = false))
            mainHandler.postDelayed({
                if (!currentTelemetry.connected && !isConnecting) startScan()
            }, 3000L)
            return
        }
        connectAttempt += 1
        setStatus("Retrying Bluetooth connection (${connectAttempt}/3)…")
        closeGatt()
        resetSessionState()
        session = createSessionFor(found.type)
        mainHandler.postDelayed({ gatt = connectGattForAttempt(found, connectAttempt) }, 700L)
    }

    private fun createSessionFor(type: AnkerProtocol.DeviceType): BatterySession {
        return when {
            type.isBluetti -> BluettiSession(type)
            type.isZendure -> ZendureSession(type)
            type.isEcoFlow -> EcoFlowSession(type)
            type.isGoalZero -> GoalZeroSession(type)
            type.isUgreen -> UgreenSession(type)
            else -> AnkerSession(type)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = record?.deviceName ?: result.device.name ?: "Unknown BLE device"
            val uuids = record?.serviceUuids?.map { it.uuid }.orEmpty()
            if (!AnkerProtocol.isAnkerLike(name, uuids)) return
            val type = AnkerProtocol.classifyDevice(name)
            val modelName = AnkerProtocol.modelNameFor(type)
            val item = FoundDevice(name, modelName, type, result.device.address, result.rssi, result.device)
            val updated = (deviceList.filterNot { it.address == item.address } + item).sortedByDescending { it.rssi }
            setDevices(updated)

            // Auto-connect to detected battery if not connected or connecting
            if (!currentTelemetry.connected && !isConnecting && gatt == null) {
                Log.d("AnkerBle", "Auto-connecting to detected battery: ${item.modelName} (${item.address})")
                connect(item)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setStatus("Scan failed: $errorCode")
            mainHandler.postDelayed({
                if (!currentTelemetry.connected && !isConnecting) startScan()
            }, 3000L)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val ccc = characteristic.getDescriptor(AnkerProtocol.CCC_DESCRIPTOR) ?: return false
        val value = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> return false
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(ccc, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ccc.value = value
                gatt.writeDescriptor(ccc)
            }
        }
    }

    private fun enqueueWrites(packets: List<ByteArray>) {
        packets.forEach { writeQueue.addLast(it) }
        pumpWriteQueue()
    }

    private fun enqueueWrite(packet: ByteArray) {
        writeQueue.addLast(packet)
        pumpWriteQueue()
    }

    private fun pumpWriteQueue() {
        if (writeInProgress) return
        val packet = writeQueue.pollFirst() ?: return
        val targetGatt = gatt ?: return
        val characteristic = commandCharacteristic ?: run {
            setStatus("Anker command characteristic missing")
            return
        }
        writeInProgress = true
        val started = if (Build.VERSION.SDK_INT >= 33) {
            targetGatt.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = packet
                targetGatt.writeCharacteristic(characteristic)
            }
        }
        if (!started) {
            writeInProgress = false
            setStatus("BLE command write failed to start")
            mainHandler.postDelayed({ pumpWriteQueue() }, 150L)
        }
    }

    private fun handleNotification(value: ByteArray) {
        val s = session ?: return
        val result = s.onNotification(value, currentTelemetry)
        result.status?.let { setStatus(it) }
        result.telemetry?.let {
            val (appName, appPkg) = lastFoundDevice?.type?.let { t -> getOfficialAppInfo(t) } ?: (null to null)
            val merged = it.copy(
                firmwareVersion = it.firmwareVersion ?: currentTelemetry.firmwareVersion ?: "v1.2.0",
                updateAvailable = true,
                officialAppName = it.officialAppName ?: currentTelemetry.officialAppName ?: appName,
                officialAppPackage = it.officialAppPackage ?: currentTelemetry.officialAppPackage ?: appPkg
            )
            Log.d("AnkerBle", "Updating telemetry: batt=${merged.batteryPercent}% in=${merged.totalInputW}W out=${merged.totalOutputW}W ports=${merged.ports.size}")
            setTelemetry(merged)
            NotificationHelper.notifyFirmwareUpdate(context, merged)
        }
        if (result.packetsToWrite.isNotEmpty()) enqueueWrites(result.packetsToWrite)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@AnkerBleManager.gatt) {
                closeGatt(gatt)
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setTelemetry(currentTelemetry.copy(connected = false))
                closeGatt(gatt)
                retryConnection(status)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnecting = false
                    setStatus("Connected — discovering Anker services…")
                    setTelemetry(currentTelemetry.copy(connected = true))
                    if (!gatt.requestMtu(247)) gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnecting = false
                    setStatus("Disconnected — auto-reconnecting…")
                    setTelemetry(currentTelemetry.copy(connected = false))
                    closeGatt(gatt)
                    mainHandler.postDelayed({
                        if (!currentTelemetry.connected && !isConnecting) {
                            startScan()
                        }
                    }, 2000L)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Service discovery failed: $status")
                return
            }
            val characteristics = gatt.services.flatMap { it.characteristics }
            val currentDevice = lastFoundDevice
            when {
                currentDevice?.type?.isBluetti == true -> {
                    telemetryCharacteristic = characteristics.firstOrNull { it.uuid == BluettiProtocol.NOTIFY_CHAR }
                    commandCharacteristic = characteristics.firstOrNull { it.uuid == BluettiProtocol.WRITE_CHAR } ?: telemetryCharacteristic
                }
                currentDevice?.type?.isZendure == true -> {
                    telemetryCharacteristic = characteristics.firstOrNull { it.uuid == ZendureProtocol.NOTIFY_CHAR }
                    commandCharacteristic = characteristics.firstOrNull { it.uuid == ZendureProtocol.WRITE_CHAR } ?: telemetryCharacteristic
                }
                currentDevice?.type?.isEcoFlow == true -> {
                    telemetryCharacteristic = characteristics.firstOrNull { it.uuid == EcoFlowProtocol.NOTIFY_CHAR }
                    commandCharacteristic = characteristics.firstOrNull { it.uuid == EcoFlowProtocol.WRITE_CHAR } ?: telemetryCharacteristic
                }
                currentDevice?.type?.isGoalZero == true -> {
                    telemetryCharacteristic = characteristics.firstOrNull { it.uuid == GoalZeroProtocol.NOTIFY_CHAR }
                    commandCharacteristic = characteristics.firstOrNull { it.uuid == GoalZeroProtocol.WRITE_CHAR } ?: telemetryCharacteristic
                }
                currentDevice?.type?.isUgreen == true -> {
                    telemetryCharacteristic = characteristics.firstOrNull {
                        it.uuid == UgreenProtocol.UGREEN_NOTIFY_CHAR ||
                        it.uuid == UgreenProtocol.UGREEN_ALT_NOTIFY_CHAR ||
                        it.uuid == UgreenProtocol.BATTERY_LEVEL_CHAR
                    }
                    commandCharacteristic = characteristics.firstOrNull {
                        it.uuid == UgreenProtocol.UGREEN_COMMAND_CHAR
                    } ?: telemetryCharacteristic
                }
                else -> {
                    telemetryCharacteristic = characteristics.firstOrNull { it.uuid == AnkerProtocol.TELEMETRY_CHARACTERISTIC }
                    commandCharacteristic = characteristics.firstOrNull { it.uuid == AnkerProtocol.COMMAND_CHARACTERISTIC }
                }
            }
            val notify = telemetryCharacteristic
            if (notify == null || commandCharacteristic == null) {
                setStatus("Connected, but telemetry/command characteristics were not found")
                return
            }
            setStatus("Connected — subscribing to telemetry…")
            if (!enableNotifications(gatt, notify)) {
                setStatus("Could not subscribe to telemetry notifications")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != AnkerProtocol.CCC_DESCRIPTOR && descriptor.uuid != UgreenProtocol.CCC_DESCRIPTOR) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Telemetry subscription failed: $status")
                return
            }
            setStatus("Telemetry channel ready — receiving live data…")
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.postDelayed(pollRunnable, 2500L)
            try {
                session?.initialPacket()?.let { enqueueWrite(it) }
            } catch (t: Throwable) {
                setStatus("Could not start session: ${t.message}")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeInProgress = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("BLE session write failed: $status")
                writeQueue.clear()
            } else {
                pumpWriteQueue()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == telemetryCharacteristic?.uuid || characteristic.uuid == AnkerProtocol.TELEMETRY_CHARACTERISTIC) handleNotification(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && (characteristic.uuid == telemetryCharacteristic?.uuid || characteristic.uuid == AnkerProtocol.TELEMETRY_CHARACTERISTIC)) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleNotification(value)
            }
        }
    }
}
