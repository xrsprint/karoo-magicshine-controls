package com.lenne0815.karoomagicshine.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LightActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = when (intent.action) {
            ACTION_TOGGLE_100 ->
                Intent(context, MagicshineControlService::class.java)
                    .setAction(MagicshineControlService.ACTION_TOGGLE_100)
            else -> null
        } ?: return

        context.startService(serviceIntent)
    }

    companion object {
        const val ACTION_TOGGLE_100 = "com.lenne0815.karoomagicshine.action.LIGHT_TOGGLE_100"
    }
}
