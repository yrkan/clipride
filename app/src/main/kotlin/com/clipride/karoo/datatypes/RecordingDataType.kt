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
import com.clipride.karoo.datatypes.glance.formatDuration
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
class RecordingDataType(
    extension: String,
    private val bleManager: GoProBleManager,
) : DataTypeImpl(extension, "gopro-recording") {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val toggleIntent = Intent(ClipRideActionReceiver.ACTION_TOGGLE_RECORDING).apply {
            component = ComponentName("com.clipride", "com.clipride.karoo.ClipRideActionReceiver")
        }

        val job = CoroutineScope(Dispatchers.IO).launch {
            // Render initial state immediately so view is not blank on screen return
            try {
                val result = glance.compose(context, DpSize.Unspecified) {
                    RecordingView(
                        bleManager.connectionState.value,
                        bleManager.isRecording.value,
                        bleManager.displayDuration.value,
                        toggleIntent,
                    )
                }
                emitter.updateView(result.remoteViews)
            } catch (e: Exception) {
                Timber.w(e, "RecordingDataType: initial render failed")
            }

            combine(
                bleManager.connectionState,
                bleManager.isRecording,
                bleManager.displayDuration,
            ) { state, recording, duration ->
                Triple(state, recording, duration)
            }.conflate().collect { (state, recording, duration) ->
                try {
                    val result = glance.compose(context, DpSize.Unspecified) {
                        RecordingView(state, recording, duration, toggleIntent)
                    }
                    emitter.updateView(result.remoteViews)
                } catch (e: Exception) {
                    Timber.w(e, "RecordingDataType: render failed")
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}

@Composable
private fun RecordingView(
    state: GoProConnectionState,
    isRecording: Boolean,
    durationSeconds: Int,
    toggleIntent: Intent,
) {
    DataFieldContainer {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionSendBroadcast(toggleIntent)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state != GoProConnectionState.CONNECTED -> {
                    NoDataText()
                }

                !isRecording -> {
                    ValueText(
                        text = "\u25B6 REC",
                        fontSize = 22.sp,
                        color = GlanceColors.TextPrimary,
                    )
                    LabelText(
                        text = "tap to start",
                        color = GlanceColors.TextDim,
                        fontSize = 11.sp,
                    )
                }

                else -> {
                    ValueText(
                        text = "\u25A0 ${formatDuration(durationSeconds)}",
                        fontSize = 26.sp,
                        color = GlanceColors.RecordingActive,
                    )
                    LabelText(
                        text = "tap to stop",
                        color = GlanceColors.RecordingActive.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
