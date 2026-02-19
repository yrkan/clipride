package com.clipride.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object GoProUuid {
    private const val BASE = "b5f9%s-aa8d-11e3-9046-0002a5d5c51b"

    fun fromShort(short: String): Uuid = Uuid.parse(BASE.format(short))

    // GoPro BLE advertising service UUID (scan filter)
    val GOPRO_SERVICE: Uuid = Uuid.parse("0000fea6-0000-1000-8000-00805f9b34fb")

    // Command channel: write commands (shutter, highlight, mode, etc.)
    val CQ_COMMAND: Uuid = fromShort("0072")
    // Command response: notifications with command results
    val CQ_COMMAND_RSP: Uuid = fromShort("0073")

    // Setting channel: write setting changes (resolution, fps, keep-alive)
    val CQ_SETTING: Uuid = fromShort("0074")
    // Setting response: notifications with setting results
    val CQ_SETTING_RSP: Uuid = fromShort("0075")

    // Query channel: poll/register status updates
    val CQ_QUERY: Uuid = fromShort("0076")
    // Query response: notifications with status values
    val CQ_QUERY_RSP: Uuid = fromShort("0077")

    // WiFi AP credentials (read triggers pairing dialog)
    val WAP_SSID: Uuid = fromShort("0002")
    val WAP_PASSWORD: Uuid = fromShort("0003")

    // Network management (SetPairingComplete protobuf)
    val CN_NETWORK_MGMT: Uuid = fromShort("0091")
    val CN_NETWORK_MGMT_RSP: Uuid = fromShort("0092")

    // Full GoPro BLE service UUID (for service discovery filter)
    val GOPRO_BASE_SERVICE: Uuid = fromShort("0001")

    // All response characteristics for notification subscription
    val RESPONSE_UUIDS = listOf(CQ_COMMAND_RSP, CQ_SETTING_RSP, CQ_QUERY_RSP)
}
