package com.clipride.karoo.datatypes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProConnectionState
import com.clipride.karoo.ClipRideActionReceiver
import com.clipride.karoo.datatypes.glance.DataFieldContainer
import com.clipride.karoo.datatypes.glance.GlanceColors
import com.clipride.karoo.datatypes.glance.LabelText
import com.clipride.karoo.datatypes.glance.NoDataText
import com.clipride.karoo.datatypes.glance.ValueText
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class PhotoDataType(
    extension: String,
    private val bleManager: GoProBleManager,
) : DataTypeImpl(extension, "gopro-photo") {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val photoIntent = Intent(ClipRideActionReceiver.ACTION_TAKE_PHOTO).apply {
            component = ComponentName("com.clipride", "com.clipride.karoo.ClipRideActionReceiver")
        }

        val job = CoroutineScope(Dispatchers.IO).launch {
            // Render initial state
            try {
                val result = glance.compose(context, DpSize.Unspecified) {
                    PhotoView(
                        bleManager.connectionState.value,
                        bleManager.isRecording.value,
                        photoIntent,
                    )
                }
                emitter.updateView(result.remoteViews)
            } catch (e: Exception) {
                Timber.w(e, "PhotoDataType: initial render failed")
            }

            combine(
                bleManager.connectionState,
                bleManager.isRecording,
            ) { state, recording ->
                Pair(state, recording)
            }.conflate().collect { (state, recording) ->
                try {
                    val result = glance.compose(context, DpSize.Unspecified) {
                        PhotoView(state, recording, photoIntent)
                    }
                    emitter.updateView(result.remoteViews)
                } catch (e: Exception) {
                    Timber.w(e, "PhotoDataType: render failed")
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}

@Composable
private fun PhotoView(
    state: GoProConnectionState,
    isRecording: Boolean,
    photoIntent: Intent,
) {
    DataFieldContainer {
        val baseModifier = GlanceModifier.fillMaxSize()
        val modifier = if (isRecording && state == GoProConnectionState.CONNECTED) {
            baseModifier // No clickable — recording in progress
        } else {
            baseModifier.clickable(actionSendBroadcast(photoIntent))
        }
        Column(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state != GoProConnectionState.CONNECTED) {
                NoDataText()
            } else {
                ValueText(
                    text = "PHOTO",
                    fontSize = 28.sp,
                    color = if (isRecording) GlanceColors.TextDim else GlanceColors.BatteryGood,
                )
                LabelText(
                    text = if (isRecording) "recording" else "tap for photo",
                    color = GlanceColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
