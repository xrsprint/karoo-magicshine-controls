package com.lenne0815.karoomagicshine

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lenne0815.karoomagicshine.extension.LampCandidate
import com.lenne0815.karoomagicshine.extension.AppUiState
import com.lenne0815.karoomagicshine.extension.MagicshineControlService
import com.lenne0815.karoomagicshine.extension.SharedLightState
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private enum class OutputTarget {
        LOW,
        HIGH,
        OFF,
    }

    companion object {
        private const val PREFS_NAME = "magicshine_prefs"
        private const val PREF_SELECTED_LAMP_ADDRESS = "selected_lamp_address"
        private const val PREF_SELECTED_LAMP_NAME = "selected_lamp_name"
        private const val HORI_ANT_DEVICE_ID = "39269-35-5"
    }

    private var controlService: MagicshineControlService? = null
    private lateinit var antLightControl: KarooLightControl
    private lateinit var batteryView: TextView
    private lateinit var temperatureView: TextView
    private lateinit var changeLampButton: View
    private lateinit var changeLampLabel: TextView
    private lateinit var chooserGate: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var chooserHintView: TextView
    private lateinit var lampCandidatesLayout: LinearLayout
    private lateinit var connectButton: View
    private lateinit var connectLabelView: TextView
    private lateinit var connectStateView: TextView
    private lateinit var offButton: View
    private lateinit var level25Button: View
    private lateinit var level50Button: View
    private lateinit var level75Button: View
    private lateinit var level100Button: View
    private lateinit var sosButton: View
    private lateinit var blitzButton: View
    private lateinit var disconnectButton: View
    private lateinit var prefs: android.content.SharedPreferences
    private var currentConnectionStatus: String = "disconnected"
    private var currentBatteryStatus: String = "?"
    private var currentTemperatureStatus: String = "?"
    private var currentDisplayStatus: String = "idle"
    private var selectedModule: MagicshineModule = MagicshineModule.MODULE_1
    private var selectedOutputTarget: OutputTarget = OutputTarget.LOW
    private var selectedLevelPercent: Int? = null
    // Remember the last Low Beam brightness so HIGH BEAM can toggle back to it.
    private var lastHoriLowBeamLevelPercent: Int = 25
    private var horiHighBeamActive: Boolean = false
    private var currentSelectedLampAddress: String? = null
    private var currentSelectedLampName: String? = null
    private var lastRenderedCandidateSignature: String = ""
    private val serviceListener = object : MagicshineControlService.Listener {
        override fun onStatus(status: String) {
            runOnUiThread {
                currentDisplayStatus = displayStatus(status)
                updateConnectButton()
            }
        }

        override fun onConnectionStatus(status: String) {
            runOnUiThread {
                currentConnectionStatus = status
                updateConnectButton()
            }
        }

        override fun onBatteryStatus(status: String) {
            runOnUiThread {
                currentBatteryStatus = status
                renderBatteryStatus(status)
                updateConnectButton()
            }
        }

        override fun onTemperatureStatus(status: String) {
            runOnUiThread {
                currentTemperatureStatus = status
                temperatureView.text = status
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? MagicshineControlService.LocalBinder)?.getService() ?: return
            controlService = service
            service.registerListener(serviceListener)
            restoreSelectedLamp()
            if (hasPermissions()) {
                service.startDiscovery()
            }
            refreshUiFromController()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controlService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            restoreSelectedLamp()
            controlService?.startDiscovery()
        } else {
            currentDisplayStatus = "permissions"
            updateConnectButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        antLightControl = KarooLightControl(this).also { it.bind() }

        changeLampButton = findViewById(R.id.btnChangeLamp)
        changeLampLabel = findViewById(R.id.txtChangeLampLabel)
        chooserGate = findViewById(R.id.lampChooserGate)
        controlPanel = findViewById(R.id.layoutControlPanel)
        chooserHintView = findViewById(R.id.txtChooserHint)
        lampCandidatesLayout = findViewById(R.id.layoutLampCandidates)
        batteryView = findViewById(R.id.txtBattery)
        temperatureView = findViewById(R.id.txtTemperature)
        connectButton = findViewById(R.id.btnConnect)
        connectLabelView = findViewById(R.id.txtConnectLabel)
        connectStateView = findViewById(R.id.txtConnectState)
        offButton = findViewById(R.id.btnOff)
        level25Button = findViewById(R.id.btnLevel25)
        level50Button = findViewById(R.id.btnLevel50)
        level75Button = findViewById(R.id.btnLevel75)
        level100Button = findViewById(R.id.btnLevel100)
        sosButton = findViewById(R.id.btnSos)
        blitzButton = findViewById(R.id.btnBlitz)
        disconnectButton = findViewById(R.id.btnDisconnect)
        bindService(
            Intent(this, MagicshineControlService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        if (!hasPermissions()) {
            ensurePermissions()
        }
        lifecycleScope.launch {
            while (true) {
                refreshUiFromController()
                delay(500)
            }
        }

        connectButton.setOnClickListener {
            connectIfPermitted()
        }
        changeLampButton.setOnClickListener {
            controlService?.stopRepeatingCommand()
            controlService?.disconnect()
            saveSelectedLamp(null)
            controlService?.setPreferredAddress(null)
            controlService?.startDiscovery(forceRestart = true)
            refreshLampSelectionUi()
        }
        // HORI 1300 dedicated controls: ON/OFF | LOW | MED | HIGH | HIGH BEAM
        sosButton.visibility = View.GONE
        blitzButton.visibility = View.GONE
        disconnectButton.visibility = View.GONE

        // Hori 1300 manual controls: five full-width bars stacked vertically.
        // CONNECT and ON/OFF are already arranged side-by-side in the XML.
        // The four mode buttons occupy the dedicated vertical mode container.
        val modeContainer = level25Button.parent as? LinearLayout
        if (modeContainer != null) {
            modeContainer.orientation = LinearLayout.VERTICAL
            modeContainer.gravity = android.view.Gravity.CENTER_HORIZONTAL

            val buttons = listOf(level25Button, level50Button, level75Button, level100Button)
            buttons.forEachIndexed { index, button ->
                (button.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = LinearLayout.LayoutParams.MATCH_PARENT
                    height = dpToPx(50)
                    weight = 0f
                    leftMargin = 0
                    rightMargin = 0
                    topMargin = if (index == 0) 0 else dpToPx(4)
                    bottomMargin = 0
                }
                button.layoutParams = button.layoutParams
            }
        }

        offButton.setOnClickListener { toggleHoriPower() }
        level25Button.setOnClickListener { sendHoriMode(Hori1300Mode.LOW) }
        level50Button.setOnClickListener { sendHoriMode(Hori1300Mode.MED) }
        level75Button.setOnClickListener { sendHoriMode(Hori1300Mode.HIGH) }
        level100Button.setOnClickListener { sendHoriMode(Hori1300Mode.HIGH_BEAM) }
        disconnectButton.setOnClickListener {
            controlService?.stopRepeatingCommand()
            controlService?.disconnect()
        }
    }

    private fun toggleHoriPower() {
        controlService?.stopRepeatingCommand()
        if (selectedOutputTarget == OutputTarget.OFF) {
            // Restore the last Hori 1300 lighting mode instead of always waking at LOW.
            // The Hori 1300 itself also has a memory function, so this keeps the app
            // state aligned with the last mode selected through the app.
            val lastLevel = SharedLightState.get(this).lastOnLevelPercent ?: 25
            val lastMode = when (lastLevel) {
                100 -> Hori1300Mode.HIGH_BEAM
                75 -> Hori1300Mode.HIGH
                50 -> Hori1300Mode.MED
                else -> Hori1300Mode.LOW
            }
            sendHoriMode(lastMode)
        } else {
            selectedOutputTarget = OutputTarget.OFF
            selectedLevelPercent = null
            SharedLightState.set(this, SharedLightState.OutputTarget.OFF, null)
            updateOutputControls()
            updateBrightnessControls()
            horiHighBeamActive = false
            sendHoriControlCommands(listOf(MagicshineProtocol.buildHoriControlBeam(false)))
            sendAntMode("OFF")
        }
    }

    private fun sendHoriMode(mode: Hori1300Mode) {
        if (mode == Hori1300Mode.HIGH_BEAM) {
            if (!hasPermissions()) {
                ensurePermissions()
                Toast.makeText(this, "Grant Bluetooth permissions first", Toast.LENGTH_SHORT).show()
                return
            }
            if (horiHighBeamActive) {
                horiHighBeamActive = false
                selectedOutputTarget = OutputTarget.LOW
                selectedLevelPercent = lastHoriLowBeamLevelPercent
                SharedLightState.set(this, SharedLightState.OutputTarget.LOW, lastHoriLowBeamLevelPercent)
                updateOutputControls()
                updateBrightnessControls()
                sendHoriControlCommands(listOf(MagicshineProtocol.buildHoriControlBeam(false)))
            } else {
                val currentLevel = selectedLevelPercent
                    ?.takeIf { it in setOf(25, 50, 75) }
                    ?: lastHoriLowBeamLevelPercent
                lastHoriLowBeamLevelPercent = currentLevel
                horiHighBeamActive = true
                selectedOutputTarget = OutputTarget.LOW
                selectedLevelPercent = 100
                SharedLightState.set(this, SharedLightState.OutputTarget.LOW, 100)
                updateOutputControls()
                updateBrightnessControls()
                sendHoriControlCommands(listOf(MagicshineProtocol.buildHoriControlBeam(true)))
            }
            return
        }

        val level = when (mode) {
            Hori1300Mode.LOW -> 25
            Hori1300Mode.MED -> 50
            Hori1300Mode.HIGH -> 75
            Hori1300Mode.HIGH_BEAM -> 100
        }
        val antMode = when (mode) {
            Hori1300Mode.LOW -> "STEADY4"
            Hori1300Mode.MED -> "STEADY3"
            Hori1300Mode.HIGH -> "STEADY2"
            Hori1300Mode.HIGH_BEAM -> error("handled above")
        }

        val wasHighBeam = horiHighBeamActive
        horiHighBeamActive = false
        lastHoriLowBeamLevelPercent = level
        selectedOutputTarget = OutputTarget.LOW
        selectedLevelPercent = level
        SharedLightState.set(this, SharedLightState.OutputTarget.LOW, level)
        updateOutputControls()
        updateBrightnessControls()

        // Factory LOW/MED/HIGH are controlled through Karoo's native ANT+ light
        // service. Only clear the BLE high-beam flag when we are actually leaving
        // high beam; otherwise normal ANT mode changes need no BLE transaction.
        if (wasHighBeam) {
            sendHoriControlCommands(listOf(MagicshineProtocol.buildHoriControlBeam(false)))
        }
        sendAntMode(antMode)
    }

    private fun sendAntMode(modeName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = antLightControl.setLightMode(HORI_ANT_DEVICE_ID, modeName)
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "ANT light command unavailable: $modeName",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun sendHoriLowBeamThenBeamMode(lowBeamFrame: String, highBeam: Boolean) {
        if (!hasPermissions()) {
            ensurePermissions()
            Toast.makeText(this, "Grant Bluetooth permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSelectedLampAddress == null) {
            Toast.makeText(this, "Select a lamp first", Toast.LENGTH_SHORT).show()
            return
        }
        controlService?.sendHoriLowBeamThenBeamMode(lowBeamFrame, highBeam)
    }

    private fun sendHoriControlCommands(commands: List<String>) {
        if (!hasPermissions()) {
            ensurePermissions()
            Toast.makeText(this, "Grant Bluetooth permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSelectedLampAddress == null) {
            Toast.makeText(this, "Select a lamp first", Toast.LENGTH_SHORT).show()
            return
        }
        controlService?.sendHoriControl(commands)
    }

    override fun onResume() {
        super.onResume()
        AppUiState.setActive(this, true)
        applySharedLightState()
        updateOutputControls()
        updateBrightnessControls()
    }

    override fun onPause() {
        AppUiState.setActive(this, false)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            AppUiState.setActive(this, false)
        }
        controlService?.unregisterListener(serviceListener)
        antLightControl.unbind()
        runCatching { unbindService(serviceConnection) }
        if (isFinishing) {
            controlService = null
        }
        super.onDestroy()
    }

    private fun connectIfPermitted() {
        if (!hasPermissions()) {
            ensurePermissions()
            Toast.makeText(this, "Grant Bluetooth permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSelectedLampAddress == null) {
            Toast.makeText(this, "Select a lamp first", Toast.LENGTH_SHORT).show()
            return
        }
        if (controlService?.hasLiveConnection() == true) {
            return
        }
        controlService?.retryDiscoveryAndConnectFromUi()
    }

    private fun sendIfPermitted(frame: String) {
        if (!hasPermissions()) {
            ensurePermissions()
            Toast.makeText(this, "Grant Bluetooth permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSelectedLampAddress == null) {
            Toast.makeText(this, "Select a lamp first", Toast.LENGTH_SHORT).show()
            return
        }
        controlService?.send(frame)
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions() {
        if (!hasPermissions()) permissionLauncher.launch(requiredPermissions())
    }

    private fun refreshUiFromController() {
        refreshLampSelectionUi()
        applySharedLightState()
        val service = controlService ?: return
        val connectionStatus = service.currentConnectionStatus()
        val batteryStatus = service.currentBatteryStatus()
        val temperatureStatus = service.currentTemperatureStatus()
        val status = service.currentStatus()

        if (currentConnectionStatus != connectionStatus) {
            currentConnectionStatus = connectionStatus
        }
        if (currentBatteryStatus != batteryStatus) {
            currentBatteryStatus = batteryStatus
            renderBatteryStatus(batteryStatus)
        }
        if (currentTemperatureStatus != temperatureStatus) {
            currentTemperatureStatus = temperatureStatus
            temperatureView.text = temperatureStatus
        }
        val displayStatus = displayStatus(status)
        val effectiveDisplayStatus = when {
            connectionStatus == "connected" -> "connected"
            connectionStatus == "connecting" -> "connecting"
            currentSelectedLampAddress != null &&
                displayStatus in setOf("idle", "searching", "disconnected") -> "found"
            else -> displayStatus
        }
        if (currentDisplayStatus != effectiveDisplayStatus) {
            currentDisplayStatus = effectiveDisplayStatus
        }
        updateConnectButton()
        updateOutputControls()
        updateBrightnessControls()
    }

    private fun renderBatteryStatus(status: String) {
        batteryView.text = status
        val color = when (status) {
            "HIGH" -> R.color.karoo_ui_green
            "MID" -> R.color.karoo_orange
            "LOW" -> R.color.karoo_ui_red
            else -> R.color.white
        }
        batteryView.setTextColor(getColor(color))
    }

    private fun applySharedLightState() {
        val snapshot = SharedLightState.get(this)
        selectedOutputTarget = when (snapshot.outputTarget) {
            SharedLightState.OutputTarget.LOW -> {
                selectedModule = MagicshineModule.MODULE_1
                OutputTarget.LOW
            }
            SharedLightState.OutputTarget.HIGH -> {
                selectedModule = MagicshineModule.MODULE_2
                OutputTarget.HIGH
            }
            SharedLightState.OutputTarget.OFF -> {
                selectedModule = if (snapshot.lastOnTarget == SharedLightState.OutputTarget.HIGH) {
                    MagicshineModule.MODULE_2
                } else {
                    MagicshineModule.MODULE_1
                }
                OutputTarget.OFF
            }
        }
        selectedLevelPercent = snapshot.levelPercent
    }

    private fun restoreSelectedLamp() {
        val savedAddress = prefs.getString(PREF_SELECTED_LAMP_ADDRESS, null)
        val savedName = prefs.getString(PREF_SELECTED_LAMP_NAME, null)
        currentSelectedLampAddress = savedAddress
        currentSelectedLampName = savedName
        controlService?.setPreferredAddress(savedAddress)
    }

    private fun saveSelectedLamp(address: String?, name: String? = null) {
        currentSelectedLampAddress = address
        currentSelectedLampName = name
        prefs.edit()
            .putString(PREF_SELECTED_LAMP_ADDRESS, address)
            .putString(PREF_SELECTED_LAMP_NAME, name)
            .apply()
    }

    private fun refreshLampSelectionUi() {
        val service = controlService
        val selectedLamp = service?.currentSelectedLamp()
        val candidates = service?.currentLampCandidates() ?: emptyList()
        val preferredAddress = service?.currentPreferredAddress() ?: currentSelectedLampAddress
        currentSelectedLampAddress = preferredAddress
        if (selectedLamp != null && currentSelectedLampName != selectedLamp.name) {
            currentSelectedLampName = selectedLamp.name
            prefs.edit().putString(PREF_SELECTED_LAMP_NAME, selectedLamp.name).apply()
        }

        val hasSelection = preferredAddress != null
        chooserGate.visibility = if (hasSelection) android.view.View.GONE else android.view.View.VISIBLE
        controlPanel.visibility = if (hasSelection) android.view.View.VISIBLE else android.view.View.GONE
        changeLampButton.visibility = if (hasSelection) android.view.View.VISIBLE else android.view.View.GONE
        changeLampLabel.text = selectedLamp?.name ?: currentSelectedLampName ?: "Switch lamp"

        chooserHintView.text = if (candidates.isEmpty()) {
            "Searching for M2-B0 / M1-B0 lamps"
        } else {
            "Tap to select"
        }

        val signature = candidates.joinToString("|") { "${it.address}:${it.name}" }
        if (signature == lastRenderedCandidateSignature) return
        lastRenderedCandidateSignature = signature

        lampCandidatesLayout.removeAllViews()
        candidates.forEach { candidate ->
            val button = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(44),
                ).also { it.topMargin = dpToPx(4) }
                text = formatLampCandidate(candidate)
                textSize = 11f
                setOnClickListener {
                    saveSelectedLamp(candidate.address, candidate.name)
                    controlService?.setPreferredAddress(candidate.address)
                    controlService?.startDiscovery(forceRestart = true)
                    refreshLampSelectionUi()
                }
            }
            lampCandidatesLayout.addView(button)
        }
    }

    private fun updateConnectButton() {
        connectLabelView.text = when (currentConnectionStatus) {
            "connected" -> "CONNECTED"
            "connecting" -> "CONNECT"
            else -> "CONNECT"
        }
        connectStateView.text = currentDisplayStatus
        val background = when {
            currentConnectionStatus == "connected" || currentDisplayStatus == "connected" ->
                R.drawable.bg_connect_connected
            currentDisplayStatus == "found" -> R.drawable.bg_connect_found
            else -> R.drawable.bg_connect_idle
        }
        connectButton.setBackgroundResource(background)
    }

    private fun updateBrightnessControls() {
        level25Button.setBackgroundResource(
            if (selectedLevelPercent == 25) R.drawable.bg_module_selected else R.drawable.bg_lamp_switch,
        )
        level50Button.setBackgroundResource(
            if (selectedLevelPercent == 50) R.drawable.bg_module_selected else R.drawable.bg_lamp_switch,
        )
        level75Button.setBackgroundResource(
            if (selectedLevelPercent == 75) R.drawable.bg_module_selected else R.drawable.bg_lamp_switch,
        )
        level100Button.setBackgroundResource(
            if (selectedLevelPercent == 100) R.drawable.bg_module_selected else R.drawable.bg_lamp_switch,
        )
    }

    private fun sendLevel(percent: Int) {
        controlService?.stopRepeatingCommand()
        if (selectedOutputTarget == OutputTarget.OFF) {
            selectedOutputTarget = if (selectedModule == MagicshineModule.MODULE_2) {
                OutputTarget.HIGH
            } else {
                OutputTarget.LOW
            }
        }
        selectedLevelPercent = percent
        SharedLightState.set(
            this,
            if (selectedModule == MagicshineModule.MODULE_2) {
                SharedLightState.OutputTarget.HIGH
            } else {
                SharedLightState.OutputTarget.LOW
            },
            percent,
        )
        updateOutputControls()
        updateBrightnessControls()
        sendIfPermitted(MagicshineProtocol.buildPresetFrame(selectedModule, percent))
    }

    private fun startModeLoop(mode: MagicshineMode) {
        controlService?.stopRepeatingCommand()
        val preservedLevel = selectedLevelPercent ?: SharedLightState.get(this).lastOnLevelPercent ?: 100
        if (selectedOutputTarget == OutputTarget.OFF) {
            selectedOutputTarget = if (selectedModule == MagicshineModule.MODULE_2) {
                OutputTarget.HIGH
            } else {
                OutputTarget.LOW
            }
        }
        selectedLevelPercent = preservedLevel
        SharedLightState.set(
            this,
            if (selectedModule == MagicshineModule.MODULE_2) {
                SharedLightState.OutputTarget.HIGH
            } else {
                SharedLightState.OutputTarget.LOW
            },
            preservedLevel,
            when (mode) {
                MagicshineMode.STEADY -> SharedLightState.Mode.STEADY
                MagicshineMode.SOS -> SharedLightState.Mode.SOS
                MagicshineMode.BLITZ -> SharedLightState.Mode.BLITZ
            },
        )
        updateOutputControls()
        updateBrightnessControls()
        val module = selectedModule
        val frame = MagicshineProtocol.buildModeFrame(module, mode)
        sendIfPermitted(frame)
        controlService?.startRepeatingCommand(frame, 1500L)
    }

    private fun resendSelectedLevelForCurrentModule() {
        val percent = selectedLevelPercent ?: return
        syncSharedStateForCurrentSelection()
        sendIfPermitted(MagicshineProtocol.buildPresetFrame(selectedModule, percent))
    }

    private fun syncSharedStateForCurrentSelection() {
        when (selectedOutputTarget) {
            OutputTarget.OFF -> SharedLightState.set(this, SharedLightState.OutputTarget.OFF, null)
            OutputTarget.LOW,
            OutputTarget.HIGH -> {
                val percent = selectedLevelPercent ?: return
                SharedLightState.set(
                    this,
                    if (selectedModule == MagicshineModule.MODULE_2) {
                        SharedLightState.OutputTarget.HIGH
                    } else {
                        SharedLightState.OutputTarget.LOW
                    },
                    percent,
                )
            }
        }
    }

    private fun updateOutputControls() {
        val module1Selected = selectedOutputTarget == OutputTarget.LOW
        val module2Selected = selectedOutputTarget == OutputTarget.HIGH
        val offSelected = selectedOutputTarget == OutputTarget.OFF
        offButton.setBackgroundResource(
            if (offSelected) R.drawable.bg_module_selected else R.drawable.bg_module_idle,
        )
        (offButton as? android.view.ViewGroup)?.getChildAt(1)?.let { (it as? TextView)?.text = "ON/OFF" }
        (level25Button as? android.view.ViewGroup)?.getChildAt(0)?.let { (it as? TextView)?.text = "LOW" }
        (level50Button as? android.view.ViewGroup)?.getChildAt(0)?.let { (it as? TextView)?.text = "MED" }
        (level75Button as? android.view.ViewGroup)?.getChildAt(0)?.let { (it as? TextView)?.text = "HIGH" }
        (level100Button as? android.view.ViewGroup)?.getChildAt(0)?.let { (it as? TextView)?.text = "HIGH BEAM" }
    }

    private fun displayStatus(raw: String): String = when {
        raw.startsWith("seen[") -> "searching"
        raw.startsWith("discovery")
            || raw.startsWith("waiting for target")
            || raw.startsWith("scanning...")
            || raw.startsWith("search") -> "searching"
        raw.startsWith("target cached")
            || raw.startsWith("found") -> "found"
        raw.startsWith("connected")
            || raw.startsWith("sync telemetry")
            || raw.startsWith("writing")
            || raw.startsWith("write ok")
            || raw.startsWith("send requested")
            || raw == "connected" -> "connected"
        raw.startsWith("disconnect") || raw == "disconnected" -> "disconnected"
        raw.startsWith("missing bluetooth permissions") -> "permissions"
        raw.startsWith("ble error") || raw.startsWith("sync error") -> "error"
        raw.startsWith("no target") || raw.startsWith("no device") -> "searching"
        else -> raw
    }

    private fun formatLampCandidate(candidate: LampCandidate): String =
        "${candidate.name} · ${shortAddress(candidate.address)}"

    private fun shortAddress(address: String): String =
        address.takeLast(8)

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
