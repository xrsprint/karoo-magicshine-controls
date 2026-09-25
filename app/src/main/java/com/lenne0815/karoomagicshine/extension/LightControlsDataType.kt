package com.lenne0815.karoomagicshine.extension

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.lenne0815.karoomagicshine.MainActivity
import com.lenne0815.karoomagicshine.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class LightControlsDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    private data class ButtonUi(
        val label: String,
        val background: Color,
        val allowTwoLines: Boolean = false,
        val iconRes: Int? = null,
    )

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        context.startService(Intent(context, MagicshineControlService::class.java).setAction(MagicshineControlService.ACTION_FIELD_VISIBLE))
        val scope = CoroutineScope(Dispatchers.IO)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val viewWidth = (config.viewSize.first / density).dp
        val viewHeight = (config.viewSize.second / density).dp
        val textSize = config.textSize.toFloat().coerceIn(16f, 22f).sp
        var lastSignature: String? = null
        val job: Job = scope.launch {
            while (true) {
                val status = LightFieldState.get(context)
                val snapshot = SharedLightState.get(context)
                val signature = status + "|" + snapshot.isOn + "|" + snapshot.levelPercent + "|" + snapshot.lastOnLevelPercent
                if (signature != lastSignature) {
                    val remoteViews = glance.compose(context, DpSize(viewWidth, viewHeight)) {
                        HoriControls(snapshot, status, viewWidth, viewHeight, textSize)
                    }
                    emitter.updateView(remoteViews.remoteViews)
                    lastSignature = signature
                }
                delay(500)
            }
        }
        emitter.setCancellable {
            job.cancel()
            context.startService(Intent(context, MagicshineControlService::class.java).setAction(MagicshineControlService.ACTION_FIELD_HIDDEN))
        }
    }

    @Composable
    private fun HoriControls(snapshot: SharedLightState.Snapshot, status: String, totalWidth: Dp, totalHeight: Dp, textSize: TextUnit) {
        val gap = 2.dp
        val cellWidth = ((totalWidth.value - 8f) / 5f).coerceAtLeast(24f).dp
        val connected = status == LightFieldState.STATUS_CONNECTED
        Row(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
            HoriButton("ON/OFF", if (snapshot.isOn) GREEN_COLOR else CARD_COLOR, GlanceModifier.width(cellWidth).fillMaxHeight().clickable(actionRunCallback<ToggleLightAction>()), textSize)
            Spacer(GlanceModifier.width(gap))
            HoriButton("LOW", if (connected && snapshot.isOn && snapshot.levelPercent == 25) GREEN_COLOR else CARD_COLOR, GlanceModifier.width(cellWidth).fillMaxHeight().clickable(actionRunCallback<HoriLowAction>()), textSize)
            Spacer(GlanceModifier.width(gap))
            HoriButton("MED", if (connected && snapshot.isOn && snapshot.levelPercent == 50) GREEN_COLOR else CARD_COLOR, GlanceModifier.width(cellWidth).fillMaxHeight().clickable(actionRunCallback<HoriMedAction>()), textSize)
            Spacer(GlanceModifier.width(gap))
            HoriButton("HIGH", if (connected && snapshot.isOn && snapshot.levelPercent == 100) GREEN_COLOR else CARD_COLOR, GlanceModifier.width(cellWidth).fillMaxHeight().clickable(actionRunCallback<HoriHighAction>()), textSize)
            Spacer(GlanceModifier.width(gap))
            HoriButton("H/B", CARD_COLOR, GlanceModifier.width(cellWidth).fillMaxHeight().clickable(actionRunCallback<HoriHighBeamAction>()), textSize)
        }
    }

    @Composable
    private fun HoriButton(label: String, background: Color, modifier: GlanceModifier, textSize: TextUnit) {
        Box(modifier = modifier.background(ColorProvider(background, background)).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
            Text(text = label, maxLines = 1, style = TextStyle(color = ColorProvider(Color.White, Color.White), fontSize = textSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
        }
    }
    companion object {
        const val TYPE_ID = "DATATYPE_LIGHT_CONTROLS"
        private const val RENDER_VERSION = 15

        private val GREEN_COLOR = Color(0xFF20D39B)
        private val CARD_COLOR = Color(0xFF6B6B6B)
        private val CARD_DARK_COLOR = Color(0xFF575757)
        private val ORANGE_COLOR = Color(0xFFFF6B00)
        private val LOW_COLOR = Color(0xFFD93D3D)
    }
}
