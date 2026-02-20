package com.clipride.karoo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProCommands
import com.clipride.ble.GoProConnectionState
import com.clipride.util.FeedbackHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class ClipRideActionReceiver : BroadcastReceiver() {

    @Inject lateinit var commands: GoProCommands
    @Inject lateinit var bleManager: GoProBleManager
    @Inject lateinit var preferences: ClipRidePreferences

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_RECORDING -> {
                FeedbackHelper.hapticFeedback(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        commands.toggleRecording()
                    } catch (e: Exception) {
                        Timber.w(e, "Toggle recording from tap failed")
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_ADD_HIGHLIGHT -> {
                FeedbackHelper.hapticFeedback(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        commands.addHighlight()
                    } catch (e: Exception) {
                        Timber.w(e, "Add highlight from tap failed")
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_CYCLE_MODE -> {
                FeedbackHelper.hapticFeedback(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val currentPreset = bleManager.currentPresetGroup.value
                        when (currentPreset) {
                            1000 -> commands.loadPhotoMode()   // Video → Photo
                            1001 -> commands.loadTimelapseMode() // Photo → Timelapse
                            else -> commands.loadVideoMode()   // Timelapse/unknown → Video
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Cycle mode from tap failed")
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_TAKE_PHOTO -> {
                FeedbackHelper.hapticFeedback(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Switch to photo, capture, switch back to video
                        val wasPreset = bleManager.currentPresetGroup.value
                        if (wasPreset != 1001) {
                            commands.loadPhotoMode()
                            kotlinx.coroutines.delay(500)
                        }
                        commands.startRecording() // In photo mode, shutter = capture
                        kotlinx.coroutines.delay(500)
                        if (wasPreset != null && wasPreset != 1001) {
                            commands.loadVideoMode()
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Take photo from tap failed")
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_TOGGLE_POWER -> {
                FeedbackHelper.hapticFeedback(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val state = bleManager.connectionState.value
                        if (state == GoProConnectionState.CONNECTED) {
                            // Turn off: sleep + disconnect
                            try { commands.sleep() } catch (e: Exception) {
                                Timber.w(e, "Sleep command failed, disconnecting anyway")
                            }
                            bleManager.stopConnection()
                            Timber.d("Camera turned off via power toggle")
                        } else if (state == GoProConnectionState.DISCONNECTED) {
                            // Reconnect
                            val addr = preferences.savedDeviceAddress
                            if (addr != null) {
                                bleManager.startConnection(addr)
                                Timber.d("Reconnecting via power toggle")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Toggle power from tap failed")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_RECORDING = "com.clipride.ACTION_TOGGLE_RECORDING"
        const val ACTION_ADD_HIGHLIGHT = "com.clipride.ACTION_ADD_HIGHLIGHT"
        const val ACTION_TOGGLE_POWER = "com.clipride.ACTION_TOGGLE_POWER"
        const val ACTION_CYCLE_MODE = "com.clipride.ACTION_CYCLE_MODE"
        const val ACTION_TAKE_PHOTO = "com.clipride.ACTION_TAKE_PHOTO"
    }
}
