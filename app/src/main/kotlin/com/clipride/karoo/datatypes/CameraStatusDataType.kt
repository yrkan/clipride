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
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProConnectionState
import com.clipride.karoo.ClipRideActionReceiver
import com.clipride.karoo.datatypes.glance.DataFieldContainer
import com.clipride.karoo.datatypes.glance.GlanceColors
import com.clipride.karoo.datatypes.glance.LabelText
import com.clipride.karoo.datatypes.glance.NoDataText
import com.clipride.karoo.datatypes.glance.StatusDot
import com.clipride.karoo.datatypes.glance.ValueText
import com.clipride.karoo.datatypes.glance.formatDuration
import com.clipride.karoo.datatypes.glance.formatSdRemaining
import com.clipride.karoo.datatypes.glance.formatSdShort
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
class CameraStatusDataType(
    extension: String,
    private val bleManager: GoProBleManager,
) : DataTypeImpl(extension, "gopro-status") {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val tier = when {
            config.gridSize.second >= 30 -> Tier.FULL
            config.gridSize.second >= 15 -> Tier.HALF
            else -> Tier.QUARTER
        }

        val toggleIntent = Intent(ClipRideActionReceiver.ACTION_TOGGLE_RECORDING).apply {
            component = ComponentName("com.clipride", "com.clipride.karoo.ClipRideActionReceiver")
        }

        val job = CoroutineScope(Dispatchers.IO).launch {
            // Render initial state immediately so view is not blank on screen return
            try {
                val initialState = CameraState(
                    bleManager.connectionState.value,
                    bleManager.isRecording.value,
                    bleManager.displayDuration.value,
                    bleManager.batteryLevel.value,
                    bleManager.sdCardRemaining.value,
                    bleManager.activeHilights.value,
                )
                val result = glance.compose(context, DpSize.Unspecified) {
                    CameraStatusView(initialState, tier, toggleIntent)
                }
                emitter.updateView(result.remoteViews)
            } catch (e: Exception) {
                Timber.w(e, "CameraStatusDataType: initial render failed")
            }

            // combine() supports max 5 flows — nest with another combine
            combine(
                combine(
                    bleManager.connectionState,
                    bleManager.isRecording,
                    bleManager.displayDuration,
                ) { state, recording, duration -> Triple(state, recording, duration) },
                combine(
                    bleManager.batteryLevel,
                    bleManager.sdCardRemaining,
                    bleManager.activeHilights,
                ) { battery, sd, hilights -> Triple(battery, sd, hilights) },
            ) { (state, recording, duration), (battery, sd, hilights) ->
                CameraState(state, recording, duration, battery, sd, hilights)
            }.conflate().collect { state ->
                try {
                    val result = glance.compose(context, DpSize.Unspecified) {
                        CameraStatusView(state, tier, toggleIntent)
                    }
                    emitter.updateView(result.remoteViews)
                } catch (e: Exception) {
                    Timber.w(e, "CameraStatusDataType: render failed")
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}

private enum class Tier { FULL, HALF, QUARTER }

private data class CameraState(
    val connectionState: GoProConnectionState,
    val isRecording: Boolean,
    val recordingDuration: Int,
    val batteryLevel: Int?,
    val sdCardRemaining: Int?,
    val activeHilights: Int = 0,
)

@Composable
private fun CameraStatusView(state: CameraState, tier: Tier, toggleIntent: Intent) {
    DataFieldContainer {
        if (state.connectionState != GoProConnectionState.CONNECTED) {
            NoDataText(fontSize = 20.sp)
            return@DataFieldContainer
        }

        val tapAction = actionSendBroadcast(toggleIntent)

        when (tier) {
            Tier.FULL -> FullLayout(state, tapAction)
            Tier.HALF -> HalfLayout(state, tapAction)
            Tier.QUARTER -> QuarterLayout(state, tapAction)
        }
    }
}

@Composable
private fun FullLayout(state: CameraState, tapAction: androidx.glance.action.Action) {
    val batColor = GlanceColors.batteryColor(state.batteryLevel)
    Column(
        modifier = GlanceModifier.fillMaxSize().clickable(tapAction),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(isActive = state.isRecording)
            Text(
                text = if (state.isRecording) " REC" else " IDLE",
                style = TextStyle(
                    color = ColorProvider(
                        if (state.isRecording) GlanceColors.RecordingActive
                        else GlanceColors.RecordingIdle
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = "  ${formatDuration(state.recordingDuration)}",
                style = TextStyle(
                    color = ColorProvider(GlanceColors.TextPrimary),
                    fontSize = 18.sp,
                ),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${state.batteryLevel ?: "--"}%",
                style = TextStyle(
                    color = ColorProvider(batColor),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            if (state.isRecording && state.activeHilights > 0) {
                Text(
                    text = "  HiLite: ${state.activeHilights}",
                    style = TextStyle(
                        color = ColorProvider(GlanceColors.TextSecondary),
                        fontSize = 14.sp,
                    ),
                )
            }
        }
        LabelText(
            text = "SD: ${formatSdRemaining(state.sdCardRemaining)}",
            color = GlanceColors.TextPrimary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun HalfLayout(state: CameraState, tapAction: androidx.glance.action.Action) {
    val batColor = GlanceColors.batteryColor(state.batteryLevel)
    Column(
        modifier = GlanceModifier.fillMaxSize().clickable(tapAction),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(isActive = state.isRecording)
            Text(
                text = " ${formatDuration(state.recordingDuration)}",
                style = TextStyle(
                    color = ColorProvider(GlanceColors.TextPrimary),
                    fontSize = 16.sp,
                ),
            )
            if (state.isRecording && state.activeHilights > 0) {
                Text(
                    text = " H:${state.activeHilights}",
                    style = TextStyle(
                        color = ColorProvider(GlanceColors.TextSecondary),
                        fontSize = 14.sp,
                    ),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${state.batteryLevel ?: "--"}%",
                style = TextStyle(
                    color = ColorProvider(batColor),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = " | SD:${formatSdShort(state.sdCardRemaining)}",
                style = TextStyle(
                    color = ColorProvider(GlanceColors.TextPrimary),
                    fontSize = 14.sp,
                ),
            )
        }
    }
}

@Composable
private fun QuarterLayout(state: CameraState, tapAction: androidx.glance.action.Action) {
    val batColor = GlanceColors.batteryColor(state.batteryLevel)
    Row(
        modifier = GlanceModifier.fillMaxSize().clickable(tapAction),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusDot(isActive = state.isRecording)
        Text(
            text = " ${state.batteryLevel ?: "--"}%",
            style = TextStyle(
                color = ColorProvider(batColor),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
