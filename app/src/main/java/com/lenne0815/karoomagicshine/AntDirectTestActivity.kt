package com.lenne0815.karoomagicshine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            status.text = if (result.values.all { it }) "BLE permission ready" else "BLE permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ant = KarooLightControl(this).also { it.bind() }
        ble = MagicshineBleController(this).also { it.setPreferredAddress(HORI_BLE_ADDRESS) }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 12, 18, 18)
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = "HORI ANT + ONE-SHOT HIGH BEAM TEST\nNORMAL MODES = ANT ONLY"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(8, 16, 8, 22)
        })
        status = TextView(this).apply {
            text = "Ready"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(8, 0, 8, 18)
        }
        root.addView(status)
        addAntButton(root, "OFF — ANT OFF", "OFF")
        addAntButton(root, "LOW — ANT STEADY4", "STEADY4")
        addAntButton(root, "MED — ANT STEADY3", "STEADY3")
        addAntButton(root, "HIGH — ANT STEADY2", "STEADY2")
        root.addView(Button(this).apply {
            text = "HIGH BEAM — ONE-SHOT BLE"
            textSize = 18f
            isAllCaps = false
            setOnClickListener { toggleHighBeam() }
        }, buttonParams())
        root.addView(TextView(this).apply {
            text = "BLE is used only when HIGH BEAM is pressed. It writes only 00 + 0401/0400 to the HORI control characteristic, then disconnects."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 22, 8, 8)
        })
        setContentView(scroll)
    }

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 100
    ).apply { bottomMargin = 14 }

    private fun addAntButton(root: LinearLayout, label: String, mode: String) {
        root.addView(Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = ant.setLightMode(HORI_ANT_DEVICE_ID, mode)
                    runOnUiThread { status.text = if (ok) "ANT sent: $mode" else "ANT unavailable: $mode" }
                }
            }
        }, buttonParams())
    }

    private fun toggleHighBeam() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            Toast.makeText(this, "Grant Bluetooth permission, then press HIGH BEAM again", Toast.LENGTH_LONG).show()
            return
        }
        val requested = !highBeam
        status.text = if (requested) "High beam connecting…" else "High beam off connecting…"
        ble.setPreferredAddress(HORI_BLE_ADDRESS)
        ble.startDiscovery(forceRestart = true)
        ble.sendHoriControlOneShot(
            listOf(MagicshineProtocol.buildHoriControlBeam(requested))
        ) { ok ->
            runOnUiThread {
                if (ok) {
                    highBeam = requested
                    status.text = if (highBeam) "HIGH BEAM ON — BLE disconnected" else "HIGH BEAM OFF — BLE disconnected"
                } else {
                    status.text = "High beam command failed"
                }
            }
        }
    }

    private fun hasBlePermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBlePermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionLauncher.launch(required)
    }

    override fun onDestroy() {
        ant.unbind()
        super.onDestroy()
    }
}
