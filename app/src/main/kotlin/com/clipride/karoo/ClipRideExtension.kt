package com.clipride.karoo

import android.content.Intent
import com.clipride.BuildConfig
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProCommands
import com.clipride.karoo.datatypes.BatteryDataType
import com.clipride.karoo.datatypes.CameraStatusDataType
import com.clipride.karoo.datatypes.PowerDataType
import com.clipride.karoo.datatypes.RecordingDataType
import com.clipride.karoo.handlers.AutoRecordHandler
import com.clipride.karoo.handlers.BatteryAlertHandler
import com.clipride.karoo.handlers.HighlightEventHandler
import com.clipride.ui.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@AndroidEntryPoint
class ClipRideExtension : KarooExtension("clipride", BuildConfig.VERSION_NAME) {

    @Inject lateinit var karooSystem: KarooSystemService
    @Inject lateinit var bleManager: GoProBleManager
    @Inject lateinit var commands: GoProCommands
    @Inject lateinit var preferences: ClipRidePreferences

    private var handlersStarted = false

    override val types by lazy {
        listOf(
            BatteryDataType(extension, bleManager),
            RecordingDataType(extension, bleManager),
            CameraStatusDataType(extension, bleManager),
            PowerDataType(extension, bleManager),
        )
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val devices = ConcurrentHashMap<String, GoProDevice>()

    override fun onCreate() {
        super.onCreate()
        Timber.d("ClipRideExtension created")
        karooSystem.connect { connected ->
            if (connected) {
                Timber.d("KarooSystem connected, requesting Bluetooth")
                karooSystem.dispatch(RequestBluetooth(extension))
                startHandlers()

                // Auto-connect to saved device
                val savedAddress = preferences.savedDeviceAddress
                if (savedAddress != null) {
                    Timber.d("Auto-connecting to saved device: $savedAddress")
                    bleManager.startConnection(savedAddress)
                }
            }
        }
    }

    private fun startHandlers() {
        if (handlersStarted) return
        handlersStarted = true
        Timber.d("Starting ride handlers")
        AutoRecordHandler(serviceScope, karooSystem, bleManager, commands, preferences).start()
        BatteryAlertHandler(serviceScope, karooSystem, bleManager, preferences).start()
        HighlightEventHandler(serviceScope, karooSystem, bleManager, commands, preferences).start()
    }

    override fun startScan(emitter: Emitter<Device>) {
        Timber.d("Starting camera scan")

        // Emit saved device immediately so Karoo can connect to it
        val savedAddress = preferences.savedDeviceAddress
        val savedName = preferences.savedDeviceName
        if (savedAddress != null) {
            val device = devices.getOrPut("gopro-$savedAddress") {
                GoProDevice(bleManager, commands, karooSystem, extension, savedAddress, savedName)
            }
            emitter.onNext(device.source)
        }

        // Also scan for new/other devices
        val job = bleManager.scan()
            .onEach { (address, name) ->
                val device = devices.getOrPut("gopro-$address") {
                    GoProDevice(bleManager, commands, karooSystem, extension, address, name)
                }
                emitter.onNext(device.source)
            }
            .catch { e ->
                Timber.w(e, "Scan error")
            }
            .launchIn(serviceScope)

        emitter.setCancellable { job.cancel() }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Timber.d("Connecting device: $uid")
        val address = uid.substringAfterLast("-")
        val device = devices.getOrPut(uid) {
            GoProDevice(bleManager, commands, karooSystem, extension, address, null)
        }
        device.connect(emitter)
    }

    override fun onBonusAction(actionId: String) {
        Timber.d("Bonus action: $actionId")
        serviceScope.launch {
            when (actionId) {
                "toggle-recording" -> {
                    val result = commands.toggleRecording()
                    if (result.isFailure) {
                        Timber.w("Toggle recording failed: ${result.exceptionOrNull()?.message}")
                    }
                }
                "add-highlight" -> {
                    val result = commands.addHighlight()
                    if (result.isFailure) {
                        Timber.w("Add highlight failed: ${result.exceptionOrNull()?.message}")
                    }
                }
                "open-settings" -> {
                    val intent = Intent(this@ClipRideExtension, SettingsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                else -> Timber.w("Unknown bonus action: $actionId")
            }
        }
    }

    override fun onDestroy() {
        Timber.d("ClipRideExtension destroying")
        serviceJob.cancel()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        bleManager.disconnect()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
