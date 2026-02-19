package com.clipride.karoo

import com.clipride.R
import com.clipride.ble.CameraCompatibility
import com.clipride.ble.GoProConnectionState
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProCommands
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.ManufacturerInfo
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnManufacturerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bridge between GoPro BLE connection and Karoo DeviceEvent system.
 * Observes bleManager.connectionState and batteryLevel to emit DeviceEvents.
 * Connection lifecycle is managed by GoProBleManager (persistent connection with reconnect).
 */
class GoProDevice(
    private val bleManager: GoProBleManager,
    private val commands: GoProCommands,
    private val karooSystem: KarooSystemService,
    private val extensionId: String,
    private val address: String,
    private val name: String?
) {
    val uid = "gopro-$address"

    val source = Device(
        extension = extensionId,
        uid = uid,
        dataTypes = listOf(
            DataType.dataTypeId(extensionId, "gopro-battery"),
            DataType.dataTypeId(extensionId, "gopro-recording"),
            DataType.dataTypeId(extensionId, "gopro-status"),
        ),
        displayName = name ?: "Camera ($address)"
    )

    private var hardwareInfoSent = false

    fun connect(emitter: Emitter<DeviceEvent>) {
        // Ensure persistent connection is running (idempotent — no-op if already active)
        bleManager.startConnection(address)

        val job = CoroutineScope(Dispatchers.IO).launch {
            // Connection status events
            launch {
                bleManager.connectionState
                    .map { state ->
                        when (state) {
                            GoProConnectionState.CONNECTED -> ConnectionStatus.CONNECTED
                            GoProConnectionState.SCANNING,
                            GoProConnectionState.CONNECTING,
                            GoProConnectionState.PAIRING -> ConnectionStatus.SEARCHING
                            GoProConnectionState.DISCONNECTED -> ConnectionStatus.SEARCHING
                        }
                    }
                    .distinctUntilChanged()
                    .collect { status ->
                        emitter.onNext(OnConnectionStatus(status))
                        if (status == ConnectionStatus.CONNECTED && !hardwareInfoSent) {
                            sendHardwareInfo(emitter)
                        }
                    }
            }

            // Battery level events
            launch {
                bleManager.batteryLevel
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { level ->
                        emitter.onNext(OnBatteryStatus(BatteryStatus.fromPercentage(level)))
                    }
            }
        }

        // Don't stop connection on cancel — just stop event emission
        emitter.setCancellable { job.cancel() }
    }

    private suspend fun sendHardwareInfo(emitter: Emitter<DeviceEvent>) {
        try {
            val info = commands.getHardwareInfo().getOrNull() ?: return
            hardwareInfoSent = true

            emitter.onNext(
                OnManufacturerInfo(
                    ManufacturerInfo(
                        manufacturer = "Camera",
                        serialNumber = info.serialNumber,
                        modelNumber = info.modelNumber
                    )
                )
            )

            val capability = CameraCompatibility.detect(info.modelName)
            when (capability.supportLevel) {
                CameraCompatibility.SupportLevel.UNKNOWN -> {
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "gopro_compat_warning",
                            icon = R.drawable.ic_clipride,
                            title = "Unknown Camera",
                            detail = "Model ${info.modelName} has not been tested",
                            autoDismissMs = 5000L,
                            backgroundColor = R.color.alert_warning,
                            textColor = R.color.white,
                        ),
                    )
                }
                CameraCompatibility.SupportLevel.PARTIAL -> {
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "gopro_compat_partial",
                            icon = R.drawable.ic_clipride,
                            title = "Limited Support",
                            detail = "Some features may not be available",
                            autoDismissMs = 5000L,
                            backgroundColor = R.color.alert_warning,
                            textColor = R.color.white,
                        ),
                    )
                }
                CameraCompatibility.SupportLevel.FULL -> {
                    // No warning needed
                }
            }

            for (issue in capability.knownIssues) {
                Timber.w("Camera known issue [${issue.id}]: ${issue.description}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get hardware info")
        }
    }
}
