package com.lenne0815.karoomagicshine

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AntDirectTestActivity : AppCompatActivity() {
    companion object { private const val HORI_ANT_DEVICE_ID = "39269-35-5" }
    private lateinit var ant: KarooLightControl
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ant = KarooLightControl(this).also { it.bind() }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 10, 18, 18)
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = "HORI ANT HIGH BEAM CANDIDATE TEST\nNO BLUETOOTH"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(6, 6, 6, 10)
        })
        status = TextView(this).apply {
            text = "Ready — ANT only"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(6, 0, 6, 10)
        }
        root.addView(status)
        add(root, "OFF — ANT OFF", "OFF")
        add(root, "LOW — ANT STEADY4", "STEADY4")
        add(root, "MED — ANT STEADY3", "STEADY3")
        add(root, "HIGH — ANT STEADY2", "STEADY2")
        root.addView(TextView(this).apply {
            text = "CANDIDATES"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(6, 10, 6, 6)
        })
        add(root, "TEST — ANT STEADY1", "STEADY1")
        add(root, "TEST — ANT STEADY5", "STEADY5")
        add(root, "TEST — ANT AUTO", "AUTO")
        add(root, "TEST — ANT HIGH_BEAM", "HIGH_BEAM")
        setContentView(scroll)
    }

    private fun add(root: LinearLayout, label: String, mode: String) {
        root.addView(Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = ant.setLightMode(HORI_ANT_DEVICE_ID, mode)
                    runOnUiThread { status.text = if (ok) "ANT sent: $mode" else "ANT unavailable: $mode" }
                }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 76).apply { bottomMargin = 7 })
    }

    override fun onDestroy() {
        ant.unbind()
        super.onDestroy()
    }
}
