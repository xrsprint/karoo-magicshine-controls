package com.lenne0815.karoomagicshine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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
    private var highBeam = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.all { it }) connectBle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ant = KarooLightControl(this).also { it.bind() }
        ble = MagicshineBleController(this).also { it.setPreferredAddress(HORI_BLE_ADDRESS) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
        }
        addMode(root, "OFF", "OFF")
        addMode(root, "LOW", "STEADY4")
        addMode(root, "MED", "STEADY3")
        addMode(root, "HIGH", "STEADY2")
        root.addView(Button(this).apply {
            text = "HIGH BEAM"
            textSize = 25f
            isAllCaps = false
            setOnClickListener { toggleHighBeam() }
        }, buttonParams())
        setContentView(root)

        if (hasBlePermissions()) connectBle() else requestBlePermissions()
    }

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
    ).apply { setMargins(0, 3, 0, 3) }

    private fun addMode(root: LinearLayout, label: String, antMode: String) {
        root.addView(Button(this).apply {
            text = label
            textSize = 25f
            isAllCaps = false
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    ant.setLightMode(HORI_ANT_DEVICE_ID, antMode)
                    highBeam = false
                }
            }
        }, buttonParams())
    }

    private fun toggleHighBeam() {
        if (!ble.hasLiveConnection()) {
            connectBle()
            return
        }
        if (!highBeam) {
            ble.sendHoriControl(
                listOf(
                    MagicshineProtocol.buildHoriControlMode(15),
                    MagicshineProtocol.buildHoriControlBeam(true),
                )
            )
        } else {
            ble.sendHoriControl(listOf(MagicshineProtocol.buildHoriControlBeam(false)))
        }
        highBeam = !highBeam
    }

    private fun connectBle() {
        ble.startDiscovery(forceRestart = true)
        ble.connectHoriControlOnly()
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
