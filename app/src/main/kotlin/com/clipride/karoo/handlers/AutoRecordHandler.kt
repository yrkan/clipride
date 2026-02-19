package com.clipride.karoo.handlers

import com.clipride.R
import com.clipride.ble.GoProConnectionState
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProCommands
import com.clipride.karoo.ClipRidePreferences
import com.clipride.util.consumerFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class AutoRecordHandler(
    private val scope: CoroutineScope,
    private val karooSystem: KarooSystemService,
    private val bleManager: GoProBleManager,
    private val commands: GoProCommands,
    private val preferences: ClipRidePreferences,
) {
    private var rideState: RideState = RideState.Idle
    private var startRecordingJob: Job? = null

    fun start() {
        scope.launch {
            karooSystem.consumerFlow<RideState>().collect { newState ->
                val oldState = rideState
                rideState = newState
                handleTransition(oldState, newState)
            }
        }
    }

    private fun handleTransition(oldState: RideState, newState: RideState) {
        when {
            // Ride started
            newState is RideState.Recording && oldState is RideState.Idle -> onRideStart()
            // Ride resumed from pause
            newState is RideState.Recording && oldState is RideState.Paused -> onRideResume()
            // Ride paused
            newState is RideState.Paused -> onRidePause(newState.auto)
            // Ride ended
            newState is RideState.Idle && oldState !is RideState.Idle -> onRideEnd()
        }
    }

    private fun onRideStart() {
        if (!preferences.autoRecordEnabled) return
        if (bleManager.connectionState.value != GoProConnectionState.CONNECTED) {
            Timber.d("AutoRecord: camera not connected, skipping")
            return
        }
        if (bleManager.isRecording.value) {
            Timber.d("AutoRecord: already recording, skipping")
            return
        }

        val delayMs = preferences.autoRecordDelaySeconds * 1000L
        startRecordingJob?.cancel()
        startRecordingJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val result = commands.startRecording()
            if (result.isSuccess) {
                Timber.d("AutoRecord: recording started")
                karooSystem.dispatch(
                    InRideAlert(
                        id = "gopro_rec_started",
                        icon = R.drawable.ic_record,
                        title = "Camera: Recording",
                        detail = null,
                        autoDismissMs = 1500L,
                        backgroundColor = R.color.status_connected,
                        textColor = R.color.white,
                    ),
                )
            } else {
                Timber.w("AutoRecord: start failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun onRideResume() {
        if (!preferences.autoRecordEnabled) return
        if (!preferences.continueOnAutoPause) return
        if (bleManager.isRecording.value) return

        scope.launch {
            val result = commands.startRecording()
            if (result.isSuccess) {
                Timber.d("AutoRecord: recording resumed")
            }
        }
    }

    private fun onRidePause(auto: Boolean) {
        startRecordingJob?.cancel()
        startRecordingJob = null

        if (!preferences.autoRecordEnabled) return
        if (!bleManager.isRecording.value) return

        if (auto && preferences.continueOnAutoPause) {
            // Auto-pause (stop light) + user wants to continue → don't stop
            Timber.d("AutoRecord: auto-pause, continuing recording")
            return
        }

        scope.launch {
            val result = commands.stopRecording()
            if (result.isSuccess) {
                Timber.d("AutoRecord: recording stopped on pause")
            }
        }
    }

    private fun onRideEnd() {
        startRecordingJob?.cancel()
        startRecordingJob = null

        if (!bleManager.isRecording.value) return

        scope.launch {
            val result = commands.stopRecording()
            if (result.isSuccess) {
                Timber.d("AutoRecord: recording stopped on ride end")
            }
        }
    }
}
