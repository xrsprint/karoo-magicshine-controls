package com.lenne0815.karoomagicshine

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AntDirectTestActivity : AppCompatActivity() {
    companion object {
        private const val HORI_ANT_DEVICE_ID = "39269-35-5"
    }

    private lateinit var ant: KarooLightControl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ant = KarooLightControl(this).also { it.bind() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "HORI ANT DIRECT TEST\nNO BLUETOOTH CODE CAN RUN"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(8, 16, 8, 28)
        })
        addModeButton(root, "OFF — ANT OFF", "OFF")
        addModeButton(root, "LOW — ANT STEADY4", "STEADY4")
        addModeButton(root, "MED — ANT STEADY3", "STEADY3")
        addModeButton(root, "HIGH — ANT STEADY2", "STEADY2")
        root.addView(TextView(this).apply {
            text = "There is deliberately no CONNECT or HIGH BEAM control in this build."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8, 28, 8, 8)
        })
        setContentView(root)
    }

    private fun addModeButton(root: LinearLayout, label: String, mode: String) {
        root.addView(Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setOnClickListener { send(mode) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            100
        ).apply { bottomMargin = 14 })
    }

    private fun send(mode: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = ant.setLightMode(HORI_ANT_DEVICE_ID, mode)
            runOnUiThread {
                Toast.makeText(
                    this@AntDirectTestActivity,
                    if (ok) "ANT sent: $mode" else "ANT command unavailable: $mode",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroy() {
        ant.unbind()
        super.onDestroy()
    }
}
