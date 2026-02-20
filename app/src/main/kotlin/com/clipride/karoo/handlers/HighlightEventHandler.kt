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
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
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

    // Climb tracking
    private var lastClimbs: List<NavigationState.Climb> = emptyList()
    private var distanceTraveled = 0.0
    private val completedClimbs = mutableSetOf<Int>() // index of climbs already highlighted

    // HR zone 5 tracking
    private var hrZone5Min = Int.MAX_VALUE
    private var lastHrZone5Ms = 0L

    // Descent tracking
    private var lastDescentMs = 0L

    fun start() {
        // Track ride state + ride start/end bookmarks
        scope.launch {
            karooSystem.consumerFlow<RideState>().collect { newState ->
                val wasRiding = rideState is RideState.Recording || rideState is RideState.Paused
                val oldState = rideState
                rideState = newState

                if (newState is RideState.Recording && !wasRiding) {
                    highlightCount = 0
                    completedClimbs.clear()
                    // Ride start bookmark
                    if (preferences.highlightOnRideBookmark) {
                        addHighlightIfRecording("Ride Start", cooldownMs = 0)
                    }
                }
                if (newState is RideState.Idle && wasRiding) {
                    // Ride end bookmark
                    if (preferences.highlightOnRideBookmark) {
                        addHighlightIfRecording("Ride End", cooldownMs = 0)
                    }
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

        // Climb summit highlights
        scope.launch {
            // Track distance along route using DISTANCE stream
            karooSystem.streamDataFlow(DataType.Type.DISTANCE).collect { state ->
                val dist = (state as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@collect
                distanceTraveled = dist
                if (!preferences.highlightOnClimbSummit) return@collect
                checkClimbSummits()
            }
        }
        scope.launch {
            karooSystem.consumerFlow<OnNavigationState>().collect { navState ->
                val climbs = when (val state = navState.state) {
                    is NavigationState.NavigatingRoute -> state.climbs
                    is NavigationState.NavigatingToDestination -> state.climbs
                    else -> emptyList()
                }
                if (climbs != lastClimbs) {
                    lastClimbs = climbs
                    completedClimbs.clear()
                    Timber.d("Navigation: ${climbs.size} climbs on route")
                }
            }
        }

        // HR Zone 5 highlights
        scope.launch {
            karooSystem.consumerFlow<UserProfile>().collect { profile ->
                val zone5 = profile.heartRateZones.lastOrNull()
                hrZone5Min = zone5?.min ?: Int.MAX_VALUE
                Timber.d("UserProfile: HR zone 5 min=$hrZone5Min")
            }
        }
        scope.launch {
            karooSystem.streamDataFlow(DataType.Source.HEART_RATE).collect { state ->
                if (!preferences.highlightOnHrZone5) return@collect
                val hr = (state as? StreamState.Streaming)?.dataPoint?.singleValue?.toInt() ?: return@collect
                if (hr >= hrZone5Min) {
                    val now = System.currentTimeMillis()
                    if (now - lastHrZone5Ms >= HR_ZONE5_COOLDOWN_MS) {
                        lastHrZone5Ms = now
                        addHighlightIfRecording("HR Zone 5: ${hr}bpm", cooldownMs = 0)
                    }
                }
            }
        }

        // High-speed descent highlights
        scope.launch {
            combine(
                karooSystem.streamDataFlow(DataType.Type.SPEED),
                karooSystem.streamDataFlow(DataType.Type.ELEVATION_GRADE),
            ) { speedState, gradeState ->
                Pair(speedState, gradeState)
            }.collect { (speedState, gradeState) ->
                if (!preferences.highlightOnDescent) return@collect
                val speed = (speedState as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@collect
                val grade = (gradeState as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@collect

                if (speed >= preferences.descentSpeedThreshold && grade < -3.0) {
                    val now = System.currentTimeMillis()
                    if (now - lastDescentMs >= DESCENT_COOLDOWN_MS) {
                        lastDescentMs = now
                        addHighlightIfRecording("Descent: ${speed.toInt()} km/h", cooldownMs = 0)
                    }
                }
            }
        }
    }

    private fun checkClimbSummits() {
        for ((index, climb) in lastClimbs.withIndex()) {
            if (index in completedClimbs) continue
            val summitDistance = climb.startDistance + climb.length
            if (distanceTraveled >= summitDistance) {
                completedClimbs.add(index)
                scope.launch {
                    addHighlightIfRecording(
                        "Summit: ${climb.totalElevation.toInt()}m, ${climb.grade.toInt()}%",
                        cooldownMs = 0,
                    )
                }
            }
        }
    }

    private suspend fun addHighlightIfRecording(detail: String, cooldownMs: Long = COOLDOWN_MS) {
        if (rideState !is RideState.Recording) return
        if (!bleManager.isRecording.value) return

        // Debounce
        val now = System.currentTimeMillis()
        if (cooldownMs > 0 && now - lastHighlightMs < cooldownMs) return
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
        private const val HR_ZONE5_COOLDOWN_MS = 30_000L
        private const val DESCENT_COOLDOWN_MS = 60_000L
    }
}
