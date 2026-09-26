package com.lenne0815.karoomagicshine.extension

import com.lenne0815.karoomagicshine.Hori1300Mode
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

class HoriOffAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.startService(Intent(context, MagicshineControlService::class.java).setAction(MagicshineControlService.ACTION_HORI_OFF))
    }
}

abstract class HoriFixedModeAction : ActionCallback {
    abstract val mode: Hori1300Mode
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.startService(
            Intent(context, MagicshineControlService::class.java)
                .setAction(MagicshineControlService.ACTION_HORI_MODE)
                .putExtra(MagicshineControlService.EXTRA_HORI_MODE, mode.name),
        )
    }
}

class HoriLowAction : HoriFixedModeAction() { override val mode = Hori1300Mode.LOW }
class HoriMedAction : HoriFixedModeAction() { override val mode = Hori1300Mode.MED }
class HoriHighAction : HoriFixedModeAction() { override val mode = Hori1300Mode.HIGH }
class HoriHighBeamAction : HoriFixedModeAction() { override val mode = Hori1300Mode.HIGH_BEAM }
