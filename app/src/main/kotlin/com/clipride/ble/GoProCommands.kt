package com.clipride.ble

import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware info returned by GetHardwareInfo command.
 */
data class CameraInfo(
    val modelName: String,
    val modelNumber: String,
    val firmwareVersion: String,
    val serialNumber: String,
    val boardType: String
)

/**
 * High-level GoPro camera commands.
 * Wraps GoProBleManager's raw sendCommand/sendSetting/sendQuery into named operations.
 */
@Singleton
class GoProCommands @Inject constructor(
    private val bleManager: GoProBleManager
) {
    // --- Recording ---

    suspend fun startRecording(): Result<Unit> {
        Timber.d("Starting recording")
        return bleManager.sendCommand(CMD_SHUTTER_ON).map {
            bleManager.setRecordingOptimistic(true)
        }
    }

    suspend fun stopRecording(): Result<Unit> {
        Timber.d("Stopping recording")
        return bleManager.sendCommand(CMD_SHUTTER_OFF).map {
            bleManager.setRecordingOptimistic(false)
        }
    }

    suspend fun toggleRecording(): Result<Unit> {
        val recording = bleManager.isRecording.value
        Timber.d("toggleRecording: isRecording=$recording")
        return if (recording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    // --- Highlight ---

    suspend fun addHighlight(): Result<Unit> {
        Timber.d("Adding highlight")
        return bleManager.sendCommand(CMD_HIGHLIGHT).map { }
    }

    // --- Preset/Mode ---

    suspend fun loadVideoMode(): Result<Unit> {
        Timber.d("Loading video mode (preset 1000)")
        return bleManager.sendCommand(CMD_PRESET_VIDEO).map { }
    }

    suspend fun loadPhotoMode(): Result<Unit> {
        Timber.d("Loading photo mode (preset 1001)")
        return bleManager.sendCommand(CMD_PRESET_PHOTO).map { }
    }

    suspend fun loadTimelapseMode(): Result<Unit> {
        Timber.d("Loading timelapse mode (preset 1002)")
        return bleManager.sendCommand(CMD_PRESET_TIMELAPSE).map { }
    }

    // --- Camera info ---

    /**
     * Query camera hardware info.
     * Response payload format: [modelNameLen, modelName..., boardTypeLen, boardType...,
     *   firmwareLen, firmware..., serialLen, serial..., apSsidLen, apSsid...]
     */
    suspend fun getHardwareInfo(): Result<CameraInfo> {
        Timber.d("Getting hardware info")
        val response = bleManager.sendCommand(CMD_GET_HARDWARE_INFO)
        return response.mapCatching { resp ->
            if (resp !is BleResponse.Command || !resp.isSuccess) {
                throw IllegalStateException("GetHardwareInfo failed: status=${(resp as? BleResponse.Command)?.status}")
            }
            parseHardwareInfo(resp.payload)
        }
    }

    // --- Date/Time Sync ---

    /**
     * Sync camera date/time with current device time.
     * Command 0x0D: SetDateTime with 7-byte payload (YYYY big-endian, MM, DD, HH, MM, SS).
     */
    suspend fun syncDateTime(): Result<Unit> {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val payload = byteArrayOf(
            0x0D,
            0x07, // param length
            (year shr 8).toByte(),
            (year and 0xFF).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(),
        )
        Timber.d("Syncing date/time: ${year}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)} " +
            "${cal.get(Calendar.HOUR_OF_DAY)}:${cal.get(Calendar.MINUTE)}:${cal.get(Calendar.SECOND)}")
        return bleManager.sendCommand(payload).map { }
    }

    // --- Video Settings ---

    /**
     * Apply a single video setting by ID and value.
     * Setting payload format: [settingId, paramLen=1, value]
     */
    suspend fun applySetting(settingId: Byte, value: Int): Result<Unit> {
        val payload = byteArrayOf(settingId, 0x01, value.toByte())
        Timber.d("Applying setting: id=${settingId.toInt() and 0xFF}, value=$value")
        return bleManager.sendSetting(payload).map { }
    }

    /**
     * Apply saved video settings from preferences.
     * Skips settings with value -1 (= "don't change").
     */
    suspend fun applyVideoSettings(
        resolution: Int,
        fps: Int,
        fov: Int,
        hyperSmooth: Int,
    ): List<String> {
        val failed = mutableListOf<String>()
        if (resolution >= 0) {
            applySetting(GoProSetting.VIDEO_RESOLUTION, resolution).onFailure {
                Timber.w("Failed to set resolution: ${it.message}")
                failed.add("Resolution")
            }
        }
        if (fps >= 0) {
            applySetting(GoProSetting.FPS, fps).onFailure {
                Timber.w("Failed to set FPS: ${it.message}")
                failed.add("FPS")
            }
        }
        if (fov >= 0) {
            applySetting(GoProSetting.VIDEO_LENS, fov).onFailure {
                Timber.w("Failed to set FOV: ${it.message}")
                failed.add("FOV")
            }
        }
        if (hyperSmooth >= 0) {
            applySetting(GoProSetting.HYPERSMOOTH, hyperSmooth).onFailure {
                Timber.w("Failed to set HyperSmooth: ${it.message}")
                failed.add("HyperSmooth")
            }
        }
        return failed
    }

    // --- Power ---

    suspend fun sleep(): Result<Unit> {
        Timber.d("Putting camera to sleep")
        return bleManager.sendCommand(CMD_SLEEP).map { }
    }

    // --- WiFi ---

    suspend fun disableWiFiAP(): Result<Unit> {
        Timber.d("Disabling WiFi AP")
        return bleManager.sendCommand(CMD_AP_OFF).map { }
    }

    // --- Parsing ---

    private fun parseHardwareInfo(payload: ByteArray): CameraInfo {
        var offset = 0
        val fields = mutableListOf<String>()

        // Parse length-prefixed strings
        repeat(5) {
            if (offset >= payload.size) {
                fields.add("")
                return@repeat
            }
            val len = payload[offset].toInt() and 0xFF
            offset++
            if (offset + len > payload.size) {
                fields.add("")
                return@repeat
            }
            fields.add(String(payload, offset, len, Charsets.UTF_8))
            offset += len
        }

        return CameraInfo(
            modelName = fields.getOrElse(0) { "" },
            boardType = fields.getOrElse(1) { "" },
            firmwareVersion = fields.getOrElse(2) { "" },
            serialNumber = fields.getOrElse(3) { "" },
            modelNumber = fields.getOrElse(1) { "" } // boardType is used as model number
        )
    }

    companion object {
        // Command payloads WITHOUT length prefix — fragmentCommand() adds BLE packet header.
        // Format: [commandId, paramLength, paramValue...]

        // Shutter on: commandId=0x01, paramLen=0x01, value=0x01
        val CMD_SHUTTER_ON = byteArrayOf(0x01, 0x01, 0x01)

        // Shutter off: commandId=0x01, paramLen=0x01, value=0x00
        val CMD_SHUTTER_OFF = byteArrayOf(0x01, 0x01, 0x00)

        // Add highlight/hilight tag: commandId=0x18 (no params)
        val CMD_HIGHLIGHT = byteArrayOf(0x18)

        // Load preset group: commandId=0x3E, paramLen=0x02, presetGroupId (big-endian)
        // Video preset group = 1000 = 0x03E8
        val CMD_PRESET_VIDEO = byteArrayOf(0x3E, 0x02, 0x03, 0xE8.toByte())

        // Photo preset group = 1001 = 0x03E9
        val CMD_PRESET_PHOTO = byteArrayOf(0x3E, 0x02, 0x03, 0xE9.toByte())

        // Timelapse preset group = 1002 = 0x03EA
        val CMD_PRESET_TIMELAPSE = byteArrayOf(0x3E, 0x02, 0x03, 0xEA.toByte())

        // Get hardware info: commandId=0x3C (no params)
        val CMD_GET_HARDWARE_INFO = byteArrayOf(0x3C)

        // Sleep/power off: commandId=0x05 (no params)
        val CMD_SLEEP = byteArrayOf(0x05)

        // Disable WiFi AP: commandId=0x17, paramLen=0x01, value=0x00
        val CMD_AP_OFF = byteArrayOf(0x17, 0x01, 0x00)
    }
}
