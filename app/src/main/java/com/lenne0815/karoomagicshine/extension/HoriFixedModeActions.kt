package com.lenne0815.karoomagicshine.extension

import com.lenne0815.karoomagicshine.Hori1300Mode
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object HoriPressFeedback {
    private val _pressed = MutableStateFlow<String?>(null)
    val pressed = _pressed.asStateFlow()

    fun show(label: String) { _pressed.value = label }
    fun clear(label: String) { if (_pressed.value == label) _pressed.value = null }
}

class HoriOffAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HoriPressFeedback.show("OFF")
        context.startService(Intent(context, MagicshineControlService::class.java).setAction(MagicshineControlService.ACTION_HORI_OFF))
        delay(1000)
        HoriPressFeedback.clear("OFF")
    }
}

abstract class HoriFixedModeAction : ActionCallback {
    abstract val mode: Hori1300Mode
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HoriPressFeedback.show(
            when (mode) {
                Hori1300Mode.LOW -> "LOW"
                Hori1300Mode.MED -> "MED"
                Hori1300Mode.HIGH -> "HIGH"
                Hori1300Mode.HIGH_BEAM -> "HIGH BEAM"
            }
        )
        context.startService(
            Intent(context, MagicshineControlService::class.java)
                .setAction(MagicshineControlService.ACTION_HORI_MODE)
                .putExtra(MagicshineControlService.EXTRA_HORI_MODE, mode.name),
        )
        val label = when (mode) {
            Hori1300Mode.LOW -> "LOW"
            Hori1300Mode.MED -> "MED"
            Hori1300Mode.HIGH -> "HIGH"
            Hori1300Mode.HIGH_BEAM -> "HIGH BEAM"
        }
        delay(1000)
        HoriPressFeedback.clear(label)
    }
}

class HoriLowAction : HoriFixedModeAction() { override val mode = Hori1300Mode.LOW }
class HoriMedAction : HoriFixedModeAction() { override val mode = Hori1300Mode.MED }
class HoriHighAction : HoriFixedModeAction() { override val mode = Hori1300Mode.HIGH }
class HoriHighBeamAction : HoriFixedModeAction() { override val mode = Hori1300Mode.HIGH_BEAM }
