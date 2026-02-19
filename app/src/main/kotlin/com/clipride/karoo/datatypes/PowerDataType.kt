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
import com.clipride.karoo.datatypes.glance.ValueText
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class PowerDataType(
    extension: String,
    private val bleManager: GoProBleManager,
) : DataTypeImpl(extension, "gopro-power") {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val toggleIntent = Intent(ClipRideActionReceiver.ACTION_TOGGLE_POWER).apply {
            component = ComponentName("com.clipride", "com.clipride.karoo.ClipRideActionReceiver")
        }

        val job = CoroutineScope(Dispatchers.IO).launch {
            bleManager.connectionState.collect { state ->
                try {
                    val result = glance.compose(context, DpSize.Unspecified) {
                        PowerView(state, toggleIntent)
                    }
                    emitter.updateView(result.remoteViews)
                } catch (e: Exception) {
                    Timber.w(e, "PowerDataType: render failed")
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}

@Composable
private fun PowerView(
    state: GoProConnectionState,
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
            when (state) {
                GoProConnectionState.CONNECTED -> {
                    ValueText(
                        text = "ON",
                        fontSize = 32.sp,
                        color = GlanceColors.BatteryGood,
                    )
                }

                GoProConnectionState.SCANNING,
                GoProConnectionState.CONNECTING,
                GoProConnectionState.PAIRING -> {
                    ValueText(
                        text = when (state) {
                            GoProConnectionState.SCANNING -> "SCAN"
                            GoProConnectionState.PAIRING -> "PAIR"
                            else -> "WAIT"
                        },
                        fontSize = 28.sp,
                        color = GlanceColors.BatteryLow,
                    )
                }

                GoProConnectionState.DISCONNECTED -> {
                    ValueText(
                        text = "OFF",
                        fontSize = 32.sp,
                        color = GlanceColors.TextDim,
                    )
                }
            }
        }
    }
}
