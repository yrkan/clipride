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
class ModeDataType(
    extension: String,
    private val bleManager: GoProBleManager,
) : DataTypeImpl(extension, "gopro-mode") {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val cycleIntent = Intent(ClipRideActionReceiver.ACTION_CYCLE_MODE).apply {
            component = ComponentName("com.clipride", "com.clipride.karoo.ClipRideActionReceiver")
        }

        val job = CoroutineScope(Dispatchers.IO).launch {
            // Render initial state
            try {
                val result = glance.compose(context, DpSize.Unspecified) {
                    ModeView(
                        bleManager.connectionState.value,
                        bleManager.currentPresetGroup.value,
                        cycleIntent,
                    )
                }
                emitter.updateView(result.remoteViews)
            } catch (e: Exception) {
                Timber.w(e, "ModeDataType: initial render failed")
            }

            combine(
                bleManager.connectionState,
                bleManager.currentPresetGroup,
            ) { state, preset ->
                Pair(state, preset)
            }.conflate().collect { (state, preset) ->
                try {
                    val result = glance.compose(context, DpSize.Unspecified) {
                        ModeView(state, preset, cycleIntent)
                    }
                    emitter.updateView(result.remoteViews)
                } catch (e: Exception) {
                    Timber.w(e, "ModeDataType: render failed")
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}

@Composable
private fun ModeView(
    state: GoProConnectionState,
    presetGroup: Int?,
    cycleIntent: Intent,
) {
    DataFieldContainer {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionSendBroadcast(cycleIntent)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state != GoProConnectionState.CONNECTED) {
                NoDataText()
            } else {
                val (modeName, modeColor) = when (presetGroup) {
                    1000 -> "VIDEO" to GlanceColors.RecordingActive
                    1001 -> "PHOTO" to GlanceColors.BatteryGood
                    1002 -> "TLPS" to GlanceColors.BatteryLow
                    else -> "VIDEO" to GlanceColors.RecordingActive
                }
                ValueText(
                    text = modeName,
                    fontSize = 28.sp,
                    color = modeColor,
                )
                LabelText(
                    text = "tap to switch",
                    color = GlanceColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
