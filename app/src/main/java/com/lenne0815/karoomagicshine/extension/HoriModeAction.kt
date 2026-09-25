package com.lenne0815.karoomagicshine.extension

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

class HoriModeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val mode = parameters[MODE_KEY] ?: return
        context.startService(
            Intent(context, MagicshineControlService::class.java)
                .setAction(MagicshineControlService.ACTION_HORI_MODE)
                .putExtra(MagicshineControlService.EXTRA_HORI_MODE, mode),
        )
    }

    companion object {
        val MODE_KEY = ActionParameters.Key<String>("hori_mode")
    }
}
