package com.clipride.karoo.handlers

import com.clipride.R
import com.clipride.ble.GoProBleManager
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
    private val preferences: ClipRidePreferences,
) {
    private var rideState: RideState = RideState.Idle
    private var alertedLow = false
    private var alertedCritical = false
    private var alertedSdLow = false

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

                if (remaining < SD_LOW_THRESHOLD_SEC && !alertedSdLow) {
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
    }

    private fun resetAlerts() {
        alertedLow = false
        alertedCritical = false
        alertedSdLow = false
    }

    companion object {
        /** SD card low threshold: 5 minutes = 300 seconds of remaining recording time. */
        const val SD_LOW_THRESHOLD_SEC = 300
    }
}
