package com.lenne0815.karoomagicshine.extension

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class LightControlsDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val width = (config.viewSize.first / density).dp
        val height = (config.viewSize.second / density).dp
        val scope = CoroutineScope(Dispatchers.Main)
        val job: Job = scope.launch {
            val remoteViews = glance.compose(context, DpSize(width, height)) {
                StaticControls()
            }
            emitter.updateView(remoteViews.remoteViews)
        }
        emitter.setCancellable { job.cancel() }
    }

    @Composable
    private fun StaticControls() {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TestBox("OFF", GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionRunCallback<HoriOffAction>()))
            Spacer(GlanceModifier.size(6.dp))
            TestBox("LOW", GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionRunCallback<HoriLowAction>()))
            Spacer(GlanceModifier.size(6.dp))
            TestBox("MED", GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionRunCallback<HoriMedAction>()))
            Spacer(GlanceModifier.size(6.dp))
            TestBox("HIGH", GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionRunCallback<HoriHighAction>()))
            Spacer(GlanceModifier.size(6.dp))
            TestBox("HIGH BEAM", GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionRunCallback<HoriHighBeamAction>()))
        }
    }

    @Composable
    private fun TestBox(label: String, modifier: GlanceModifier) {
        Box(
            modifier = modifier
                .background(ColorProvider(Color(0xFF171717), Color(0xFF171717)))
                .cornerRadius(12.dp)
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = TextStyle(
                        color = ColorProvider(Color.White, Color.White),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }

    companion object {
        const val TYPE_ID = "DATATYPE_HORI1300_CONTROLS_V4"
    }
}
