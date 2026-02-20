package com.clipride.ble

/**
 * GoPro BLE status and setting ID constants.
 */
object GoProStatus {

    // --- StatusId: camera state values (register/poll via CQ_QUERY) ---

    /** Camera is currently encoding (recording/capturing). 1 byte: 0=idle, 1=encoding */
    const val ENCODING: Byte = 10

    /** System busy. 1 byte: 0=idle, 1=busy */
    const val BUSY: Byte = 8

    /** Overheating. 1 byte: 0=normal, 1=overheating */
    const val OVERHEATING: Byte = 6

    /** Cold temperature. 1 byte: 0=normal, 1=cold */
    const val COLD: Byte = 85

    /** Video recording duration in seconds. 4 bytes */
    const val VIDEO_DURATION: Byte = 13

    /** Remaining photos on SD card. 4 bytes */
    const val REMAINING_PHOTOS: Byte = 34

    /** Remaining video time on SD card (seconds). 4 bytes */
    const val REMAINING_VIDEO: Byte = 35

    /** SD card status. 1 byte: various status codes */
    const val SD_REMAINING: Byte = 54

    /** Number of hilights in current video. 1 byte */
    const val ACTIVE_HILIGHTS: Byte = 58

    /** Internal battery percentage. 1 byte: 0-100 */
    const val BATTERY_PERCENTAGE: Byte = 70

    /** System ready flag. 1 byte: 0=not ready, 1=ready */
    const val READY: Byte = 82

    /** Current flat mode ID. 4 bytes */
    const val FLATMODE: Byte = 89

    /** Current preset group. 4 bytes */
    const val PRESET_GROUP: Byte = 96

    /** Camera name (UTF-8 string). Variable length */
    const val CAMERA_NAME: Byte = 122
}

/**
 * GoPro BLE setting IDs (write via CQ_SETTING).
 */
object GoProSetting {
    const val VIDEO_RESOLUTION: Byte = 2
    const val FPS: Byte = 3
    const val AUTO_POWER_DOWN: Byte = 59
    const val LED: Byte = 91
    const val VIDEO_LENS: Byte = 121
    const val HYPERSMOOTH: Byte = (135).toByte()
}

/**
 * GoPro BLE query response IDs (first byte of CQ_QUERY_RSP payload).
 */
object GoProQueryId {
    /** Response to status value poll (sendQuery 0x13) */
    const val POLL_STATUS: Byte = 0x13

    /** Response to register for status updates (sendQuery 0x52/0x53) */
    const val REGISTER_STATUS: Byte = 0x53

    /** Async status notification (push from camera) */
    const val ASYNC_NOTIFICATION: Byte = (0x93).toByte()

    /** Response to unregister status updates (sendQuery 0x72/0x73) */
    const val UNREGISTER_STATUS: Byte = 0x73
}
