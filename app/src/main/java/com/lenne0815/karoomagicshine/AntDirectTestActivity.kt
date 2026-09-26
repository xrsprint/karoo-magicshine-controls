package com.lenne0815.karoomagicshine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lenne0815.karoomagicshine.extension.MagicshineBleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AntDirectTestActivity : AppCompatActivity() {
    companion object {
        private const val HORI_ANT_DEVICE_ID = "39269-35-5"
        private const val HORI_BLE_ADDRESS = "F9:0B:53:A0:34:93"
    }
    private lateinit var ant: KarooLightControl
    private lateinit var ble: MagicshineBleController
    private lateinit var status: TextView
    private var highBeam = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) connectBle() else status.text = "Bluetooth permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ant = KarooLightControl(this).also { it.bind() }
        ble = MagicshineBleController(
            this,
            onConnectionStatus = { s -> runOnUiThread { status.text = "BLE: $s" } }
        ).also { it.setPreferredAddress(HORI_BLE_ADDRESS) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 14, 18, 18)
        }
        root.addView(TextView(this).apply {
            text = "HORI FULL HIGH BEAM SEQUENCE TEST\nPERSISTENT BLE + ANT"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(6, 6, 6, 10)
        })
        status = TextView(this).apply {
            text = "Starting…"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(6, 0, 6, 10)
        }
        root.addView(status)
        addAnt(root, "OFF", "OFF")
        addAnt(root, "LOW", "STEADY4")
        addAnt(root, "MED", "STEADY3")
        addAnt(root, "HIGH", "STEADY2")\n        addHbTest(root, "HB A — 04 01 ONLY", listOf(MagicshineProtocol.buildHoriControlBeam(true)))\n        addHbTest(root, "HB B — 03 0F + 04 01", listOf(MagicshineProtocol.buildHoriControlMode(15), MagicshineProtocol.buildHoriControlBeam(true)))
        root.addView(Button(this).apply {
            text = "HIGH BEAM"
            textSize = 17f
            isAllCaps = false
            setOnClickListener { toggleHighBeam() }
        }, params())
        root.addView(TextView(this).apply {
            text = "Compare HB A with HB B after first selecting HIGH. HB B adds control-mode 0x0F before high-beam ON. No FFE0, battery, temperature, notifications or telemetry."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 4)
        })
        setContentView(root)

        if (hasBlePermissions()) connectBle() else requestBlePermissions()
    }

    private fun params() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 78
    ).apply { bottomMargin = 8 }

    private fun addAnt(root: LinearLayout, label: String, mode: String) {
        root.addView(Button(this).apply {
            text = label
            textSize = 17f
            isAllCaps = false
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = ant.setLightMode(HORI_ANT_DEVICE_ID, mode)
                    runOnUiThread { status.text = if (ok) "ANT sent: $mode — BLE remains connected" else "ANT unavailable: $mode" }
                }
            }
        }, params())
    }

    private fun addHbTest(root: LinearLayout, label: String, commands: List<String>) {
        root.addView(Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setOnClickListener {
                if (!ble.hasLiveConnection()) {
                    status.text = "BLE disconnected — reconnecting"
                    connectBle()
                } else {
                    ble.sendHoriControl(commands)
                    highBeam = true
                    status.text = "$label sent"
                }
            }
        }, params())
    }

    private fun connectBle() {
        status.text = "BLE: connecting control-only…"
        ble.startDiscovery(forceRestart = true)
        ble.connectHoriControlOnly { ok ->
            runOnUiThread { status.text = if (ok) "BLE: CONNECTED — high beam ready" else "BLE: connection failed" }
        }
    }

    private fun toggleHighBeam() {
        if (!ble.hasLiveConnection()) {
            status.text = "BLE disconnected — reconnecting"
            connectBle()
            return
        }
        val requested = !highBeam
        ble.sendHoriControl(listOf(MagicshineProtocol.buildHoriControlBeam(requested)))
        highBeam = requested
        status.text = if (requested) "HIGH BEAM ON" else "HIGH BEAM OFF"
    }

    private fun hasBlePermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBlePermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionLauncher.launch(required)
    }

    override fun onDestroy() {
        ant.unbind()
        super.onDestroy()
    }
}
