package com.clipride.karoo.handlers

import com.clipride.R
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProCommands
import com.clipride.karoo.ClipRidePreferences
import com.clipride.util.consumerFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

class BatteryAlertHandler(
    private val scope: CoroutineScope,
    private val karooSystem: KarooSystemService,
    private val bleManager: GoProBleManager,
    private val commands: GoProCommands,
    private val preferences: ClipRidePreferences,
) {
    private data class BatteryReading(val level: Int, val timestampMs: Long)

    private var rideState: RideState = RideState.Idle
    private var alertedLow = false
    private var alertedCritical = false
    private var alertedSdLow = false
    private var alertedSdFull = false
    private var alertedOverheat = false
    private var alertedCold = false
    private var alertedPredictive = false
    private val readings = mutableListOf<BatteryReading>()

    fun start() {
        // Track ride state
        scope.launch {
            karooSystem.consumerFlow<RideState>().collect { newState ->
                val wasRiding = rideState is RideState.Recording || rideState is RideState.Paused
                rideState = newState
                // Reset alerts on new ride
                if (newState is RideState.Recording && !wasRiding) {
                    resetAlerts()
                }
            }
        }

        // Battery level monitoring
        scope.launch {
            bleManager.batteryLevel.collect { level ->
                if (level == null) return@collect
                if (rideState !is RideState.Recording) return@collect

                val critThreshold = preferences.batteryCriticalThreshold
                val lowThreshold = preferences.batteryLowThreshold

                // Track drain rate while recording
                if (bleManager.isRecording.value) {
                    val now = System.currentTimeMillis()
                    readings.add(BatteryReading(level, now))
                    if (readings.size > MAX_READINGS) {
                        readings.removeAt(0)
                    }
                    checkBatteryPrediction(level)
                }

                if (level <= critThreshold && !alertedCritical) {
                    alertedCritical = true
                    Timber.d("Battery critical: $level%")
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "gopro_battery_critical",
                            icon = R.drawable.ic_battery_critical,
                            title = "Camera: Battery!",
                            detail = "$level%",
                            autoDismissMs = 5000L,
                            backgroundColor = R.color.alert_critical,
                            textColor = R.color.white,
                        ),
                    )
                } else if (level <= lowThreshold && !alertedLow) {
                    alertedLow = true
                    Timber.d("Battery low: $level%")
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "gopro_battery_low",
                            icon = R.drawable.ic_battery_low,
                            title = "Camera: Battery",
                            detail = "$level%",
                            autoDismissMs = 3000L,
                            backgroundColor = R.color.alert_warning,
                            textColor = R.color.white,
                        ),
                    )
                }
            }
        }

        // SD card monitoring
        scope.launch {
            bleManager.sdCardRemaining.collect { remaining ->
                if (remaining == null) return@collect
                if (rideState !is RideState.Recording) return@collect

                if (remaining <= SD_FULL_THRESHOLD_SEC && !alertedSdFull) {
                    alertedSdFull = true
                    Timber.d("SD card full: ${remaining}s remaining")
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "gopro_sd_full",
                            icon = R.drawable.ic_sd_warning,
                            title = "Camera: SD Full!",
                            detail = "No space for recording",
                            autoDismissMs = 8000L,
                            backgroundColor = R.color.alert_critical,
                            textColor = R.color.white,
                        ),
                    )
                } else if (remaining < SD_LOW_THRESHOLD_SEC && !alertedSdLow) {
                    alertedSdLow = true
                    val mins = remaining / 60
                    Timber.d("SD card low: ${remaining}s (~${mins} min) remaining")
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "gopro_sd_low",
                            icon = R.drawable.ic_sd_warning,
                            title = "Camera: SD Card",
                            detail = if (mins > 0) "$mins min remaining" else "<1 min remaining",
                            autoDismissMs = 5000L,
                            backgroundColor = R.color.alert_critical,
                            textColor = R.color.white,
                        ),
                    )
                }
            }
        }

        // Overheating monitoring
        scope.launch {
            bleManager.isOverheating.collect { overheating ->
                if (overheating && !alertedOverheat) {
                    alertedOverheat = true
                    Timber.w("Camera OVERHEATING — auto-stopping recording")
                    // Auto-stop recording to protect camera
                    if (bleManager.isRecording.value) {
                        try {
                            commands.stopRecording()
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to auto-stop recording on overheat")
                        }
                    }
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "gopro_overheat",
                            icon = R.drawable.ic_thermal,
                            title = "Camera: Overheating!",
                            detail = "Recording stopped",
                            autoDismissMs = 8000L,
                            backgroundColor = R.color.alert_critical,
                            textColor = R.color.white,
                        ),
                    )
                } else if (!overheating && alertedOverheat) {
                    alertedOverheat = false
                    Timber.d("Camera cooled down")
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "gopro_cooled",
                            icon = R.drawable.ic_thermal,
                            title = "Camera: Cooled Down",
                            detail = "Ready to record",
                            autoDismissMs = 3000L,
                            backgroundColor = R.color.status_connected,
                            textColor = R.color.white,
                        ),
                    )
                }
            }
        }

        // Clear battery readings when recording stops (within same ride)
        scope.launch {
            bleManager.isRecording.collect { recording ->
                if (!recording) {
                    readings.clear()
                    alertedPredictive = false
                }
            }
        }

        // Cold monitoring
        scope.launch {
            bleManager.isCold.collect { cold ->
                if (cold && !alertedCold) {
                    alertedCold = true
                    Timber.d("Camera cold temperature warning")
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "gopro_cold",
                            icon = R.drawable.ic_thermal,
                            title = "Camera: Cold",
                            detail = "Battery life may be reduced",
                            autoDismissMs = 5000L,
                            backgroundColor = R.color.alert_cold,
                            textColor = R.color.white,
                        ),
                    )
                } else if (!cold && alertedCold) {
                    alertedCold = false
                }
            }
        }
    }

    private fun checkBatteryPrediction(currentLevel: Int) {
        if (alertedPredictive) return
        if (readings.size < MIN_READINGS) return

        val oldest = readings.first()
        val newest = readings.last()
        val elapsedMs = newest.timestampMs - oldest.timestampMs
        val elapsedMinutes = elapsedMs / 60_000.0

        if (elapsedMinutes < MIN_TRACKING_MINUTES) return

        val drainedPercent = oldest.level - newest.level
        if (drainedPercent <= 0) return // Battery not draining (charging or stable)

        val drainPerMin = drainedPercent / elapsedMinutes
        val remainingMin = (currentLevel / drainPerMin).toInt()

        if (remainingMin < PREDICTION_THRESHOLD_MIN) {
            alertedPredictive = true
            Timber.d("Battery prediction: ~${remainingMin} min remaining (drain ${String.format("%.1f", drainPerMin)}%/min)")
            karooSystem.dispatch(
                InRideAlert(
                    id = "gopro_battery_predict",
                    icon = R.drawable.ic_battery_low,
                    title = "Camera: ~${remainingMin} min left",
                    detail = "Based on current drain",
                    autoDismissMs = 5000L,
                    backgroundColor = R.color.alert_warning,
                    textColor = R.color.white,
                ),
            )
        }
    }

    private fun resetAlerts() {
        alertedLow = false
        alertedCritical = false
        alertedSdLow = false
        alertedSdFull = false
        alertedPredictive = false
        readings.clear()
        // Don't reset thermal alerts — they are state-driven, not ride-driven
    }

    companion object {
        /** SD card full threshold: effectively no space left. */
        const val SD_FULL_THRESHOLD_SEC = 10
        /** SD card low threshold: 5 minutes = 300 seconds of remaining recording time. */
        const val SD_LOW_THRESHOLD_SEC = 300
        /** Max battery readings to keep for drain rate calculation. */
        const val MAX_READINGS = 20
        /** Minimum readings needed for prediction. */
        const val MIN_READINGS = 3
        /** Minimum tracking time in minutes before prediction is valid. */
        const val MIN_TRACKING_MINUTES = 10.0
        /** Alert when predicted remaining time is below this (minutes). */
        const val PREDICTION_THRESHOLD_MIN = 30
    }
}
