package com.ben.ankerbattery

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.ben.ankerbattery.ble.AnkerBleManager
import com.ben.ankerbattery.data.UsageLogger
import com.ben.ankerbattery.model.BatteryTelemetry
import com.ben.ankerbattery.protocol.AnkerProtocol
import com.ben.ankerbattery.protocol.FirmwareCatalog
import java.io.File
import java.util.Locale

class MainActivity : Activity(), AnkerBleManager.Listener {
    private lateinit var ble: AnkerBleManager
    private lateinit var logger: UsageLogger
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var deviceContainer: LinearLayout
    private var lastLoggedAt = 0L
    private var currentTab = 0
    private val requestCodeBluetooth = 1001
    private var showingIntro = false
    private var introVideoView: VideoView? = null

    private val bg = Color.rgb(2, 14, 28)
    private val cyan = Color.rgb(0, 220, 255)
    private val cyanSoft = Color.rgb(112, 235, 255)
    private val white = Color.rgb(238, 248, 255)
    private val muted = Color.rgb(142, 176, 194)
    private val green = Color.rgb(0, 245, 170)
    private val red = Color.rgb(255, 45, 92)
    private val amber = Color.rgb(255, 185, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        ble = AnkerBleManager(this)
        logger = UsageLogger(this)
        ble.listener = this

        val prefs = getSharedPreferences("battery_manager_prefs", Context.MODE_PRIVATE)
        val hasSeenIntro = prefs.getBoolean("has_seen_intro_v1", false)

        if (!hasSeenIntro) {
            showIntroVideo(fromSettings = false)
        } else {
            renderCurrentTab()
            ble.dispatchCurrentState()
            ensurePermissionThenScan()
        }
    }

    override fun onResume() {
        super.onResume()
        if (showingIntro) {
            introVideoView?.start()
        } else if (!ble.telemetry.connected && !ble.isScanning) {
            ensurePermissionThenScan()
        }
    }

    override fun onPause() {
        super.onPause()
        if (showingIntro) {
            try { introVideoView?.pause() } catch (_: Throwable) {}
        }
    }

    private fun showIntroVideo(fromSettings: Boolean = false) {
        showingIntro = true
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        introVideoView = videoView
        frame.addView(videoView)

        val finishIntro = {
            if (showingIntro) {
                showingIntro = false
                try { videoView.stopPlayback() } catch (_: Throwable) {}
                introVideoView = null
                getSharedPreferences("battery_manager_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_seen_intro_v1", true)
                    .apply()
                renderCurrentTab()
                ble.dispatchCurrentState()
                if (!fromSettings) {
                    ensurePermissionThenScan()
                }
            }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(16))
            addView(text("BATTERY MANAGER", 13f, true, cyanSoft), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val skipBtn = text("Skip ›", 14f, true, white).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = roundRect(Color.argb(160, 10, 30, 50), cyan, 1, 16)
                setOnClickListener { finishIntro() }
            }
            addView(skipBtn)
        }
        frame.addView(topBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        videoView.setOnCompletionListener {
            finishIntro()
        }

        videoView.setOnErrorListener { _, _, _ ->
            finishIntro()
            true
        }

        try {
            val videoUri = Uri.parse("android.resource://$packageName/${R.raw.intro_video}")
            videoView.setVideoURI(videoUri)
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                videoView.start()
            }
        } catch (_: Throwable) {
            finishIntro()
        }

        setContentView(frame)
    }

    override fun onDestroy() {
        ble.listener = null
        ble.disconnect()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundRect(fill: Int, stroke: Int = cyan, strokeDp: Int = 1, radiusDp: Int = 18): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(strokeDp), stroke)
        }
    }

    private fun gradientCard(radiusDp: Int = 22): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(8, 50, 82), Color.rgb(2, 22, 40))
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.rgb(0, 175, 235))
        }
    }

    private fun text(value: String, size: Float = 16f, bold: Boolean = false, color: Int = white): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun glowText(value: String, size: Float, color: Int = white, shadowColor: Int = cyan, bold: Boolean = true): TextView {
        return text(value, size, bold, color).apply {
            gravity = Gravity.CENTER
            setShadowLayer(dp(8).toFloat(), 0f, 0f, shadowColor)
        }
    }

    private fun space(heightDp: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun baseScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
            setBackgroundColor(bg)
        }
    }

    private fun setScrollable(content: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    private fun pill(label: String, color: Int = cyan): TextView {
        return text(label, 12f, true, color).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundRect(Color.rgb(5, 31, 53), color, 1, 18)
        }
    }

    private fun neonButton(label: String, danger: Boolean = false, onClick: () -> Unit): TextView {
        val color = if (danger) red else cyan
        return text(label, 15f, true, color).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundRect(if (danger) Color.rgb(52, 7, 25) else Color.rgb(3, 37, 60), color, 1, 18)
            setOnClickListener { onClick() }
        }
    }

    private fun titleBar(title: String, subtitle: String? = null, showBack: Boolean = false, onBack: (() -> Unit)? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (showBack) {
                addView(text("‹", 38f, false, cyan).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { onBack?.invoke() }
                }, LinearLayout.LayoutParams(dp(42), dp(48)))
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 20f, true))
                if (subtitle != null) addView(text(subtitle, 12f, true, cyan))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill("BLE", cyan))
        }
    }

    private fun renderCurrentTab() {
        when (currentTab) {
            0 -> if (ble.telemetry.connected) showDashboard(ble.telemetry) else showScanner()
            1 -> showStats()
            2 -> showTools()
            3 -> showSettings()
            else -> showScanner()
        }
    }

    private fun showScanner() {
        root = baseScreen()
        root.addView(titleBar("BATTERY", "MANAGER"))
        root.addView(space(10))
        root.addView(text("Power what matters", 13f, false, muted))
        root.addView(space(18))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = gradientCard(26)
        }
        hero.addView(text("POWER LINK", 12f, true, cyan))
        hero.addView(text("Battery command center", 26f, true))
        hero.addView(space(8))
        hero.addView(text("Scanning automatically for nearby battery packs and power stations. Batteries you've connected before stay listed — tap one to connect.", 14f, false, muted))
        root.addView(hero)
        root.addView(space(14))

        statusText = pill(ble.status.ifBlank { "Ready" }, statusColor(ble.status))
        root.addView(statusText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(space(14))
        root.addView(text("Nearby devices", 18f, true))
        root.addView(space(10))

        deviceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceContainer)
        root.addView(space(18))
        root.addView(bottomNav())
        setScrollable(root)
        renderDevices(ble.devices)
    }

    private fun ensurePermissionThenScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            ble.startScan()
        } else {
            requestPermissions(missing.toTypedArray(), requestCodeBluetooth)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestCodeBluetooth) {
            val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
            if (btGranted) ble.startScan()
            else ble.reportPermissionDenied()
        }
    }

    private fun batteryImageForType(type: AnkerProtocol.DeviceType): ImageView {
        return ImageView(this).apply {
            val res = when (type) {
                AnkerProtocol.DeviceType.UGREEN_NEXODE_165W, AnkerProtocol.DeviceType.UGREEN_GENERIC -> R.drawable.ugreen_nexode_20k
                AnkerProtocol.DeviceType.BLUETTI_POWER_STATION -> R.drawable.solix_c200
                AnkerProtocol.DeviceType.ZENDURE_SOLARFLOW -> R.drawable.solix_c200
                AnkerProtocol.DeviceType.ECOFLOW_DELTA -> R.drawable.solix_c200
                AnkerProtocol.DeviceType.GOAL_ZERO_YETI -> R.drawable.solix_c200
                AnkerProtocol.DeviceType.PRIME_20K -> R.drawable.prime_20k
                AnkerProtocol.DeviceType.PRIME_26K -> R.drawable.prime_26k
                AnkerProtocol.DeviceType.PRIME_27K -> R.drawable.prime_27k
                AnkerProtocol.DeviceType.SOLIX_C200 -> R.drawable.solix_c200
                AnkerProtocol.DeviceType.UNKNOWN -> R.drawable.ugreen_nexode_20k
            }
            setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
    }

    private fun renderDevices(devices: List<AnkerBleManager.FoundDevice>) {
        if (!::deviceContainer.isInitialized) return
        deviceContainer.removeAllViews()
        if (devices.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(24), dp(18), dp(24))
                background = gradientCard()
                addView(text("No batteries detected", 18f, true))
                addView(space(5))
                addView(text("Scanning… keep the battery awake with Bluetooth enabled.", 13f, false, muted))
            }
            deviceContainer.addView(empty)
            return
        }

        devices.forEach { found ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(14), dp(12))
                background = gradientCard()
            }
            c.addView(batteryImageForType(found.type), LinearLayout.LayoutParams(dp(92), dp(112)).apply {
                rightMargin = dp(10)
            })
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(found.modelName, 17f, true))
                addView(text(found.name, 11f, false, muted))
                addView(space(4))
                if (found.inRange) {
                    addView(text("●  Available", 12f, true, green))
                    addView(space(4))
                    val (sigText, sigColor) = formatSignalFriendly(found.rssi)
                    addView(text(sigText, 12f, true, sigColor))
                } else {
                    addView(text("○  ${lastSeenLabel(found.lastSeenMs)}", 12f, true, muted))
                    addView(space(4))
                    addView(text("Out of range — tap to try connecting", 12f, false, muted))
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(text("›", 34f, false, cyan).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(32), dp(54)))
            c.setOnClickListener { ble.connect(found) }
            deviceContainer.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
        }
    }

    private fun lastSeenLabel(ms: Long): String {
        if (ms <= 0L) return "Seen before"
        val mins = (System.currentTimeMillis() - ms) / 60_000L
        return when {
            mins < 1 -> "Last seen just now"
            mins < 60 -> "Last seen ${mins}m ago"
            mins < 1440 -> "Last seen ${mins / 60}h ago"
            else -> "Last seen ${mins / 1440}d ago"
        }
    }

    private fun formatSignalFriendly(rssi: Int): Pair<String, Int> {
        return when {
            rssi >= -55 -> "Signal  ●●●●  Excellent" to green
            rssi >= -70 -> "Signal  ●●●○  Good" to cyan
            rssi >= -85 -> "Signal  ●●○○  Fair" to amber
            else -> "Signal  ●○○○  Weak" to muted
        }
    }

    private fun showDashboard(t: BatteryTelemetry) {
        root = baseScreen()
        val subtitle = if (t.address.isNotBlank()) "${t.deviceName}  •  ${t.address}" else (if (t.connected) "${t.deviceName}  •  Connected" else t.deviceName)
        root.addView(titleBar(t.modelName, subtitle, true) {
            // Leaving the dashboard drops the link; otherwise the next telemetry packet re-opens it.
            currentTab = 0
            ble.disconnect()
            ensurePermissionThenScan()
        })
        root.addView(space(12))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(16))
            background = gradientCard(28)
        }

        val type = AnkerProtocol.classifyDevice(t.modelName)
        hero.addView(buildBatteryHeroImage(type, t))

        val headline = when {
            t.batteryPercent != null -> String.format(Locale.US, "Live battery: %.0f%%", t.batteryPercent)
            t.packetsReceived > 0 -> "Live connected"
            else -> "Waiting for data"
        }
        hero.addView(text(headline, 18f, true, cyanSoft).apply { gravity = Gravity.CENTER })

        val infoLine = when {
            t.temperatureC != null || t.remainingMinutes != null -> {
                val temp = t.temperatureC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "-- °C"
                val rem = t.remainingMinutes?.let { "${it / 60}h ${it % 60}m remaining" } ?: "Time remaining --"
                "$temp  •  $rem"
            }
            t.packetsReceived > 0 -> "Telemetry packets received: ${t.packetsReceived}"
            else -> "Connected successfully — listening for live telemetry"
        }
        hero.addView(text(infoLine, 13f, true, muted).apply { gravity = Gravity.CENTER })
        root.addView(hero)
        root.addView(space(12))

        if (t.updateAvailable) {
            root.addView(firmwareDisclaimerCard(t))
            root.addView(space(12))
        }

        val powerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        powerRow.addView(metricCard("↓", "Total Input", watts(t.totalInputW), cyan), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        powerRow.addView(metricCard("↑", "Total Output", watts(t.totalOutputW), cyan), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        root.addView(powerRow)
        root.addView(space(12))

        root.addView(text("PORT STATUS", 13f, true, cyan))
        root.addView(space(8))
        root.addView(buildPortsGrid(t))
        root.addView(space(6))
        root.addView(packetMonitorCard(t))
        root.addView(space(12))
        root.addView(deviceInfoCard(t))
        root.addView(space(12))

        statusText = pill(ble.status.ifBlank { "Connected" }, statusColor(ble.status))
        root.addView(statusText)
        root.addView(space(18))
        root.addView(bottomNav())
        setScrollable(root)
    }

    private fun deviceInfoCard(t: BatteryTelemetry): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = gradientCard(20)

            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(text("DEVICE IDENTIFIERS", 12f, true, cyan), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(pill("BLUETOOTH LE", cyanSoft))
            addView(header)
            addView(space(8))

            val addrRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addrRow.addView(text("MAC Address", 13f, false, muted), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addrRow.addView(text(if (t.address.isNotBlank()) t.address else "Unavailable", 13f, true, white))
            addView(addrRow)
            addView(space(4))

            val modelRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            modelRow.addView(text("Hardware Model", 13f, false, muted), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            modelRow.addView(text(t.modelName, 13f, true, white))
            addView(modelRow)
            addView(space(4))

            val fwRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val fw = t.firmwareVersion
            val fwLabel = when {
                fw == null -> "Not reported" to muted
                t.updateAvailable -> "$fw  •  Update available" to amber
                FirmwareCatalog.latestFor(AnkerProtocol.classifyDevice(t.modelName)) != null -> "$fw  •  Up to date" to green
                else -> fw to white
            }
            fwRow.addView(text("Firmware", 13f, false, muted), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fwRow.addView(text(fwLabel.first, 13f, true, fwLabel.second))
            addView(fwRow)
        }
    }

    private fun firmwareDisclaimerCard(t: BatteryTelemetry): LinearLayout {
        val officialApp = t.officialAppName ?: "Official Manufacturer App"
        val pkg = t.officialAppPackage
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundRect(Color.rgb(38, 22, 6), amber, 1, 20)

            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(text("⚠️  FIRMWARE UPDATE DETECTED", 14f, true, amber), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            headerRow.addView(pill("NEW", amber))
            addView(headerRow)
            addView(space(8))

            val latest = FirmwareCatalog.latestFor(AnkerProtocol.classifyDevice(t.modelName))
            addView(text(
                "${t.modelName} is running ${t.firmwareVersion ?: "an unknown version"}; version ${latest ?: "--"} is available.",
                13f, true, white
            ))
            addView(space(6))
            addView(text(
                "SAFETY DISCLAIMER: To protect your battery hardware, thermal safety systems, and manufacturer warranty, third-party firmware flashing is disabled. Please perform all firmware updates directly through the official $officialApp app.",
                12f, false, muted
            ))
            addView(space(12))

            addView(neonButton("Open $officialApp") {
                val launchIntent = if (pkg != null && packageManager.getLaunchIntentForPackage(pkg) != null) {
                    packageManager.getLaunchIntentForPackage(pkg)
                } else if (pkg != null) {
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                } else null

                if (launchIntent != null) {
                    try {
                        startActivity(launchIntent)
                    } catch (_: Throwable) {
                        Toast.makeText(this@MainActivity, "Could not launch $officialApp", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Please install the official $officialApp app", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun buildBatteryHeroImage(type: AnkerProtocol.DeviceType, t: BatteryTelemetry): FrameLayout {
        return FrameLayout(this).apply {
            val isPrime = type.isPrime
            val isUgreen = type.isUgreen
            val holderHeight = when {
                isPrime -> dp(250)
                isUgreen -> dp(255)
                else -> dp(235)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, holderHeight)
            val image = batteryImageForType(type)
            val imgW = when {
                isPrime -> dp(195)
                isUgreen -> dp(210)
                else -> dp(235)
            }
            val imgH = when {
                isPrime -> dp(225)
                isUgreen -> dp(235)
                else -> dp(210)
            }
            addView(image, FrameLayout.LayoutParams(imgW, imgH, Gravity.CENTER))
            when {
                isUgreen -> addUgreenOverlay(this, t)
                isPrime -> addPrimeOverlay(this, t)
                type == AnkerProtocol.DeviceType.SOLIX_C200 -> addSolixOverlay(this, t)
                else -> addGenericOverlay(this, t)
            }
        }
    }

    private fun addUgreenOverlay(parent: FrameLayout, t: BatteryTelemetry) {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val percent = t.batteryPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--%"
            val rem = t.remainingMinutes?.let { "${it / 60}H${String.format(Locale.US, "%02d", it % 60)}M" } ?: "LIVE"

            addView(text(rem, 9f, true, muted).apply { gravity = Gravity.CENTER })
            addView(glowText(percent, 22f, white, green, true))

            val c1 = t.ports.firstOrNull { it.name.contains("C1") }?.watts?.let { String.format(Locale.US, "%.1fw", it) }
                     ?: (t.totalOutputW?.let { String.format(Locale.US, "%.1fw", it) } ?: "00.0w")
            val c2 = t.ports.firstOrNull { it.name.contains("C2") }?.watts?.let { String.format(Locale.US, "%.1fw", it) } ?: "00.0w"
            val a = t.ports.firstOrNull { it.name.contains("A") }?.watts?.let { String.format(Locale.US, "%.1fw", it) } ?: "00.0w"

            addView(space(3))
            addView(text("C1  $c1", 10f, true, cyanSoft).apply { gravity = Gravity.CENTER })
            addView(text("C2  $c2", 10f, true, cyanSoft).apply { gravity = Gravity.CENTER })
            addView(text("A   $a", 10f, true, cyanSoft).apply { gravity = Gravity.CENTER })
        }
        parent.addView(screen, FrameLayout.LayoutParams(dp(94), dp(115), Gravity.CENTER).apply {
            leftMargin = -dp(14)
            topMargin = -dp(20)
        })
    }

    private fun addPrimeOverlay(parent: FrameLayout, t: BatteryTelemetry) {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(glowText("⚡", 28f, white, green, true))
            val percent = t.batteryPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--%"
            addView(glowText(percent, 24f, white, green, true))
            val flow = when {
                t.totalInputW != null -> String.format(Locale.US, "+%.0fW", t.totalInputW)
                t.totalOutputW != null -> String.format(Locale.US, "-%.0fW", t.totalOutputW)
                else -> "LIVE"
            }
            addView(glowText(flow, 11f, white, green, true))
        }
        parent.addView(screen, FrameLayout.LayoutParams(dp(86), dp(130), Gravity.CENTER).apply {
            leftMargin = -dp(24)
            topMargin = dp(10)
        })
    }

    private fun addSolixOverlay(parent: FrameLayout, t: BatteryTelemetry) {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(glowText("⚡", 26f, white, cyan, true))
            val percent = t.batteryPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--%"
            addView(glowText(percent, 28f, white, cyanSoft, true))
            val temp = t.temperatureC?.let { String.format(Locale.US, "%.0f°C", it) } ?: "LIVE"
            addView(glowText(temp, 12f, white, cyanSoft, true))
        }
        parent.addView(screen, FrameLayout.LayoutParams(dp(108), dp(95), Gravity.CENTER).apply {
            topMargin = dp(28)
        })
    }

    private fun addGenericOverlay(parent: FrameLayout, t: BatteryTelemetry) {
        val percent = t.batteryPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--%"
        parent.addView(glowText(percent, 30f, white, cyanSoft, true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun buildPortsGrid(t: BatteryTelemetry): LinearLayout {
        val portsGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ports = if (t.ports.isEmpty()) listOf(
            "C1" to null,
            "C2" to null,
            "C3" to null,
            "USB-A" to null,
            "SOLAR IN" to null,
            "AC INPUT" to null
        ) else t.ports.map { it.name to it.watts }

        ports.chunked(3).forEachIndexed { rowIndex, rowPorts ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowPorts.forEachIndexed { index, p ->
                row.addView(portCard(p.first, watts(p.second)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(5)
                    if (index < rowPorts.size - 1) rightMargin = dp(5)
                })
            }
            repeat(3 - rowPorts.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            portsGrid.addView(row)
            if (rowIndex < (ports.size - 1) / 3) portsGrid.addView(space(10))
        }
        return portsGrid
    }

    private fun metricCard(icon: String, label: String, value: String, accent: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = gradientCard()
            addView(text("$icon  $label", 12f, true, accent))
            addView(space(4))
            addView(text(value, 23f, true))
            addView(space(8))
            addView(View(this@MainActivity).apply { background = roundRect(Color.rgb(4, 83, 112), cyan, 1, 6) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))
        }
    }

    private fun packetMonitorCard(t: BatteryTelemetry): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = gradientCard()
            addView(text("PACKET MONITOR", 13f, true, cyan))
            addView(text("${t.packetsReceived} packets received", 19f, true))
            addView(space(5))
            addView(text(if (t.lastPacketHex.isBlank()) "Waiting for telemetry…" else t.lastPacketHex.take(180), 11f, false, muted))
        }
    }

    private fun portCard(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = gradientCard(18)
            addView(text("▭", 20f, true, cyan).apply { gravity = Gravity.CENTER })
            addView(text(label, 12f, true, cyanSoft).apply { gravity = Gravity.CENTER })
            addView(space(4))
            addView(text(value, 15f, true).apply { gravity = Gravity.CENTER })
        }
    }

    private fun showStats() {
        root = baseScreen()
        val t = ble.telemetry
        root.addView(titleBar("Stats", if (t.connected) t.modelName else "Usage history and live metrics"))
        root.addView(space(12))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(statCard("Battery", t.batteryPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        row1.addView(statCard("Temperature", t.temperatureC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "--"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        root.addView(row1)
        root.addView(space(10))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(statCard("Input", watts(t.totalInputW)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        row2.addView(statCard("Output", watts(t.totalOutputW)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        root.addView(row2)
        root.addView(space(10))
        root.addView(statCard("Packets Received", t.packetsReceived.toString()))
        root.addView(space(14))

        val history = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = gradientCard()
            addView(text("Recent history", 18f, true))
            addView(space(6))
            val rows = logger.readRecentLines(10)
            if (rows.isEmpty()) {
                addView(text("No saved usage history yet. Connect to a battery and let telemetry run for a bit.", 13f, false, muted))
            } else {
                rows.forEachIndexed { index, line ->
                    addView(text(line.take(110), 11f, false, muted))
                    if (index < rows.lastIndex) addView(space(6))
                }
            }
        }
        root.addView(history)
        root.addView(space(18))
        root.addView(bottomNav())
        setScrollable(root)
    }

    private fun statCard(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = gradientCard(20)
            addView(text(label, 12f, true, cyan))
            addView(space(4))
            addView(text(value, 21f, true))
        }
    }

    private fun showTools() {
        root = baseScreen()
        val t = ble.telemetry
        root.addView(titleBar("Tools", "Connection tools and packet monitor"))
        root.addView(space(12))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientCard(24)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(text("Quick actions", 18f, true))
            addView(space(10))
            addView(neonButton("Restart scan") { ble.startScan(restart = true); currentTab = 0; showScanner() })
            addView(space(10))
            addView(neonButton("Stop scan") { ble.stopScan(); Toast.makeText(this@MainActivity, "Scan stopped", Toast.LENGTH_SHORT).show(); renderCurrentTab() })
            addView(space(10))
            addView(neonButton("Forget remembered batteries", danger = true) {
                ble.forgetKnownDevices()
                Toast.makeText(this@MainActivity, "Remembered batteries cleared", Toast.LENGTH_SHORT).show()
            })
            addView(space(10))
            addView(neonButton("Rescan & Auto-Connect") { ble.startScan(autoConnect = true); Toast.makeText(this@MainActivity, "Scanning for batteries to auto-connect...", Toast.LENGTH_SHORT).show(); renderCurrentTab() })
            addView(space(10))
            addView(neonButton("Clear saved history") {
                logger.clear()
                Toast.makeText(this@MainActivity, "History cleared", Toast.LENGTH_SHORT).show()
                renderCurrentTab()
            })
        }
        root.addView(actions)
        root.addView(space(12))
        root.addView(packetMonitorCard(t))
        root.addView(space(12))
        root.addView(infoBlock("Log file", logger.historyFile().absolutePath + "\nExists: ${logger.hasHistory()}"))
        root.addView(space(12))
        root.addView(infoBlock("Device", if (t.address.isBlank()) "No connected device" else "${t.modelName}\n${t.deviceName}\n${t.address}"))
        root.addView(space(18))
        root.addView(bottomNav())
        setScrollable(root)
    }

    private fun showSettings() {
        root = baseScreen()
        root.addView(titleBar("Settings", "App status and permissions"))
        root.addView(space(12))

        root.addView(infoBlock("Bluetooth", if (ble.isBluetoothEnabled()) "Bluetooth is ON" else "Bluetooth is OFF"))
        root.addView(space(10))
        root.addView(infoBlock("Permissions", if (ble.hasPermissions()) "Bluetooth permissions granted" else "Bluetooth permissions not granted"))
        root.addView(space(10))
        root.addView(infoBlock("App version", appVersion()))
        root.addView(space(10))
        root.addView(infoBlock("Connection status", ble.status))
        root.addView(space(12))
        root.addView(neonButton("Request Bluetooth permission") { ensurePermissionThenScan() })
        root.addView(space(10))
        root.addView(neonButton("▶  Replay Intro Video") { showIntroVideo(fromSettings = true) })
        root.addView(space(10))
        root.addView(neonButton("Go to Home") { currentTab = 0; renderCurrentTab() })
        root.addView(space(18))
        root.addView(bottomNav())
        setScrollable(root)
    }

    private fun infoBlock(title: String, body: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = gradientCard(20)
            addView(text(title, 14f, true, cyan))
            addView(space(5))
            addView(text(body, 13f, false, muted))
        }
    }

    private fun appVersion(): String {
        return try {
            val info: PackageInfo = packageManager.getPackageInfo(packageName, 0)
            info.versionName ?: "1.1.0"
        } catch (_: Throwable) {
            "1.1.0"
        }
    }

    private fun bottomNav(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = gradientCard(20)
            addView(navItem(R.drawable.ic_nav_home, "Home", 0), LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(navItem(R.drawable.ic_nav_stats, "Stats", 1), LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(navItem(R.drawable.ic_nav_tools, "Tools", 2), LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(navItem(R.drawable.ic_nav_settings, "Settings", 3), LinearLayout.LayoutParams(0, dp(64), 1f))
        }
    }

    private fun navItem(iconRes: Int, label: String, tabIndex: Int): LinearLayout {
        val selected = currentTab == tabIndex
        val tint = if (selected) cyan else muted
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener {
                currentTab = tabIndex
                renderCurrentTab()
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(tint)
                alpha = if (selected) 1f else 0.86f
            }
            addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(space(4))
            addView(text(label, 11f, selected, tint).apply { gravity = Gravity.CENTER })
        }
    }

    private fun watts(value: Double?): String = value?.let { String.format(Locale.US, "%.1f W", it) } ?: "-- W"

    private fun statusColor(status: String): Int {
        val s = status.lowercase(Locale.US)
        return when {
            s.contains("error") || s.contains("failed") -> red
            s.contains("scan") || s.contains("connected") || s.contains("telemetry") -> green
            s.contains("permission") -> amber
            else -> cyan
        }
    }

    override fun onDevicesChanged(devices: List<AnkerBleManager.FoundDevice>) {
        runOnUiThread {
            if (currentTab == 0 && !ble.telemetry.connected) renderDevices(devices)
        }
    }

    override fun onTelemetryChanged(telemetry: BatteryTelemetry) {
        runOnUiThread {
            // Home tab follows the connection state: dashboard while connected, scanner otherwise.
            renderCurrentTab()
            val now = telemetry.lastUpdatedMs
            if (now > 0L && now - lastLoggedAt >= 5_000L) {
                lastLoggedAt = now
                val snapshot = telemetry
                Thread { logger.append(snapshot) }.start()
            }
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            if (::statusText.isInitialized) {
                statusText.text = status
                statusText.setTextColor(statusColor(status))
            }
            if (currentTab in 1..3) renderCurrentTab()
        }
    }
}
