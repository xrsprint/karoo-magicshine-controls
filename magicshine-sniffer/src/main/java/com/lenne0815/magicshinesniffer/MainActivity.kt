package com.lenne0815.magicshinesniffer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RequestBluetooth
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        private const val TAG = "MagicshineBleSniffer"
        private const val MAX_VISIBLE_LINES = 160
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 41
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val karooSystem by lazy { KarooSystemService(this) }
    private val visibleLines = ArrayDeque<String>()

    private lateinit var recorder: FrameRecorder
    private lateinit var sniffer: MagicshineBleSniffer
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var deviceRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recorder = FrameRecorder(this)
        sniffer = MagicshineBleSniffer(this, ::appendLog, ::showDevices)
        setContentView(buildContentView())
        appendLog("Recorder: Downloads/Magicshine/" + recorder.fileName)
        connectKarooSystem()
        requestRuntimePermissions()
    }

    override fun onDestroy() {
        sniffer.close()
        runCatching { karooSystem.disconnect() }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            appendLog("Runtime Bluetooth permissions granted=${results.all { it == PackageManager.PERMISSION_GRANTED }}")
        }
    }

    private fun buildContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(getColor(R.color.sniffer_background))
        }
        root.addView(TextView(this).apply {
            text = "Magicshine BLE Sniffer"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.sniffer_text))
        }, LinearLayout.LayoutParams(match(), wrap()))
        statusView = TextView(this).apply {
            text = "Waiting for scan..."
            textSize = 13f
            setTextColor(getColor(R.color.sniffer_muted))
            setPadding(0, dp(2), 0, dp(6))
        }
        root.addView(statusView, LinearLayout.LayoutParams(match(), wrap()))
        deviceRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceRow, LinearLayout.LayoutParams(match(), wrap()))

        root.addView(buttonRow("CONNECT", "DISCONNECT", "MARK CURRENT"))
        root.addView(TextView(this).apply {
            text = "HORI 1300 diagnostic controls"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.sniffer_text))
            setPadding(0, dp(4), 0, dp(2))
        }, LinearLayout.LayoutParams(match(), wrap()))
        root.addView(buttonRow("ALL NOTIFY", "STOP NOTIFY"))
        root.addView(buttonRow("RAPID CAPTURE", "STOP RAPID"))
        root.addView(buttonRow("RAW 63", "RAW 80", "RAW C0"))
        root.addView(buttonRow("RAW FF", "HB ON", "HB OFF"))
        root.addView(buttonRow("MODE ON", "MODE OFF", "DRAIN 100%"))
        root.addView(buttonRow("A1 TEMP", "A4 CANDIDATE", "SUPPORT SWEEP"))
        root.addView(buttonRow("AB", "AC", "AD"))
        root.addView(buttonRow("POLL A4", "STOP POLL", "OFFICIAL BURST"))
        root.addView(buttonRow("A5 PROFILES", "A9 RUNTIME", "GATT READS"))
        root.addView(buttonRow("STATE MONITOR", "STOP MONITOR", "MARK CURRENT"))

        scrollView = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.sniffer_panel))
            isFillViewport = true
        }
        logView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.sniffer_text))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scrollView.addView(logView, ViewGroup.LayoutParams(match(), wrap()))
        root.addView(scrollView, LinearLayout.LayoutParams(match(), 0, 1f))
        return root
    }

    private fun buttonRow(vararg labels: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        labels.forEach { label ->
            addView(Button(this@MainActivity).apply {
                text = label
                textSize = 10f
                setOnClickListener { handleButton(label) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        }
    }

    private fun handleButton(label: String) {
        when (label) {
            "CONNECT" -> sniffer.scan()
            "DISCONNECT" -> sniffer.disconnect()
            "MARK CURRENT" -> appendLog("========== MARK CURRENT PHYSICAL BATTERY ==========")
            "A1 TEMP" -> sniffer.send(label, MagicshineBleSniffer.QUERY_TEMPERATURE)
            "A4 CANDIDATE" -> sniffer.send(label, MagicshineBleSniffer.QUERY_BATTERY)
            "SUPPORT SWEEP" -> sniffer.runSupportSweep()
            "AB" -> sniffer.send(label, MagicshineBleSniffer.QUERY_AB)
            "AC" -> sniffer.send(label, MagicshineBleSniffer.QUERY_AC)
            "AD" -> sniffer.send(label, MagicshineBleSniffer.QUERY_AD)
            "POLL A4" -> sniffer.startBatteryPolling()
            "STOP POLL" -> sniffer.stopPolling()
            "OFFICIAL BURST" -> sniffer.runOfficialSequence()
            "A5 PROFILES" -> sniffer.send(label, MagicshineBleSniffer.QUERY_PROFILES)
            "A9 RUNTIME" -> sniffer.send(label, MagicshineBleSniffer.QUERY_ENDURANCE)
            "GATT READS" -> sniffer.runGattSurvey()
            "ALL NOTIFY" -> sniffer.startAllNotifyCapture()
            "STOP NOTIFY" -> sniffer.stopAllNotifyCapture()
            "RAPID CAPTURE" -> sniffer.startRapidHoriCapture()
            "STOP RAPID" -> sniffer.stopRapidHoriCapture()
            "STATE MONITOR" -> sniffer.startStateMonitor()
            "STOP MONITOR" -> sniffer.stopStateMonitor()
            "RAW 63" -> sniffer.sendHoriRawBrightness(0x63)
            "RAW 80" -> sniffer.sendHoriRawBrightness(0x80)
            "RAW C0" -> sniffer.sendHoriRawBrightness(0xC0)
            "RAW FF" -> sniffer.sendHoriRawBrightness(0xFF)
            "HB ON" -> sniffer.sendHoriBeam(true)
            "HB OFF" -> sniffer.sendHoriBeam(false)
            "MODE ON" -> sniffer.sendHoriMode(15)
            "MODE OFF" -> sniffer.sendHoriMode(0)
            "DRAIN 100%" -> sniffer.startFullPowerDischarge()
        }
    }

    private fun showDevices(devices: List<String>) {
        mainHandler.post {
            deviceRow.removeAllViews()
            deviceRow.visibility = android.view.View.VISIBLE
            if (devices.isEmpty()) {
                deviceRow.visibility = android.view.View.GONE
                return@post
            }
            val title = TextView(this).apply {
                text = "Select lamp to connect:"
                textSize = 12f
                setTextColor(getColor(R.color.sniffer_muted))
            }
            deviceRow.addView(title)
            devices.forEach { entry ->
                val address = entry.substringBefore(" | ")
                val button = Button(this).apply {
                    text = entry
                    textSize = 10f
                }
                button.setOnClickListener {
                    button.isEnabled = false
                    button.text = "CONNECTING… " + entry
                    deviceRow.removeAllViews()
                    deviceRow.visibility = android.view.View.GONE
                    sniffer.connect(address)
                }
                deviceRow.addView(button, LinearLayout.LayoutParams(match(), dp(44)))
            }
        }
    }

    private fun connectKarooSystem() {
        runCatching {
            karooSystem.connect { connected ->
                appendLog("KarooSystem connected=$connected")
                if (connected) {
                    runCatching {
                        karooSystem.dispatch(RequestBluetooth(MagicshineSnifferKarooExtension.EXTENSION_ID))
                    }.onFailure { appendLog("ERROR RequestBluetooth: ${it.message}") }
                }
            }
        }.onFailure { appendLog("ERROR KarooSystem: ${it.message}") }
    }

    private fun requestRuntimePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
    }

    private fun appendLog(message: String) {
        val stamped = "${timestamp()} $message"
        Log.i(TAG, stamped)
        recorder.append(stamped)
        mainHandler.post {
            visibleLines.addLast(stamped)
            while (visibleLines.size > MAX_VISIBLE_LINES) visibleLines.removeFirst()
            logView.text = visibleLines.joinToString("\n")
            statusView.text = message
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT

    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT
}
