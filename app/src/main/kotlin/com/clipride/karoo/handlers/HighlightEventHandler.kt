package com.clipride.karoo.handlers

import com.clipride.R
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProCommands
import com.clipride.karoo.ClipRidePreferences
import com.clipride.util.consumerFlow
import com.clipride.util.streamDataFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.Lap
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

class HighlightEventHandler(
    private val scope: CoroutineScope,
    private val karooSystem: KarooSystemService,
    private val bleManager: GoProBleManager,
    private val commands: GoProCommands,
    private val preferences: ClipRidePreferences,
) {
    private var rideState: RideState = RideState.Idle
    private var highlightCount = 0
    private var lastHighlightMs = 0L

    fun start() {
        // Track ride state
        scope.launch {
            karooSystem.consumerFlow<RideState>().collect { newState ->
                val wasRiding = rideState is RideState.Recording || rideState is RideState.Paused
                rideState = newState
                if (newState is RideState.Recording && !wasRiding) {
                    highlightCount = 0
                }
            }
        }

        // Lap highlights
        scope.launch {
            karooSystem.consumerFlow<Lap>().collect {
                if (preferences.highlightOnLap) {
                    addHighlightIfRecording("Lap ${it.number}")
                }
            }
        }

        // Peak power highlights
        scope.launch {
            karooSystem.streamDataFlow(DataType.Type.POWER).collect { state ->
                if (!preferences.highlightOnPeakPower) return@collect
                val power = (state as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@collect
                if (power >= preferences.peakPowerThreshold) {
                    addHighlightIfRecording("${power.toInt()}W")
                }
            }
        }

        // Max speed highlights
        scope.launch {
            karooSystem.streamDataFlow(DataType.Type.SPEED).collect { state ->
                if (!preferences.highlightOnMaxSpeed) return@collect
                val speed = (state as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@collect
                if (speed >= preferences.maxSpeedThreshold) {
                    addHighlightIfRecording("${speed.toInt()} km/h")
                }
            }
        }
    }

    private suspend fun addHighlightIfRecording(detail: String) {
        if (rideState !is RideState.Recording) return
        if (!bleManager.isRecording.value) return

        // Debounce: min 5s between auto-highlights
        val now = System.currentTimeMillis()
        if (now - lastHighlightMs < COOLDOWN_MS) return
        lastHighlightMs = now

        val result = commands.addHighlight()
        if (result.isSuccess) {
            highlightCount++
            Timber.d("Auto-highlight #$highlightCount: $detail")
            karooSystem.dispatch(
                InRideAlert(
                    id = "gopro_highlight_$highlightCount",
                    icon = R.drawable.ic_highlight,
                    title = "Camera: Highlight #$highlightCount",
                    detail = detail,
                    autoDismissMs = 1500L,
                    backgroundColor = R.color.status_connected,
                    textColor = R.color.white,
                ),
            )
        } else {
            Timber.w("Auto-highlight failed: ${result.exceptionOrNull()?.message}")
        }
    }

    companion object {
        private const val COOLDOWN_MS = 5_000L
    }
}
