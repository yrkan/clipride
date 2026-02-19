package com.clipride.ble

import no.nordicsemi.kotlin.ble.core.util.MergeResult
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * GoPro BLE protocol: command fragmentation (TX) and response assembly (RX).
 *
 * Packet header format (first byte):
 * - bit 7: continuation flag (1 = continuation packet)
 * - bits 6-5: header type (00=GENERAL, 01=EXT_13, 10=EXT_16)
 * - bits 4-0: length or length high bits
 *
 * GENERAL (<=31 bytes): 1-byte header, bits 4-0 = total length
 * EXT_13  (<=8191 bytes): 2-byte header
 * EXT_16  (<=65535 bytes): 3-byte header
 * Continuation: 0x80 prefix, remaining bytes are payload
 */
object GoProBleProtocol {

    private const val MAX_PACKET_SIZE = 20

    // --- TX: Fragment command payload into BLE packets ---

    fun fragmentCommand(payload: ByteArray, maxChunkSize: Int = MAX_PACKET_SIZE): List<ByteArray> {
        val totalLength = payload.size
        val header = buildHeader(totalLength)

        val packets = mutableListOf<ByteArray>()

        // First packet: header + initial payload
        val firstDataSize = minOf(payload.size, maxChunkSize - header.size)
        packets.add(header + payload.copyOfRange(0, firstDataSize))

        // Continuation packets: 0x80 prefix + payload chunks
        var offset = firstDataSize
        while (offset < payload.size) {
            val chunkSize = minOf(payload.size - offset, maxChunkSize - 1)
            val packet = ByteArray(1 + chunkSize)
            packet[0] = 0x80.toByte()
            payload.copyInto(packet, 1, offset, offset + chunkSize)
            packets.add(packet)
            offset += chunkSize
        }

        return packets
    }

    private fun buildHeader(totalLength: Int): ByteArray = when {
        totalLength <= 31 -> {
            // GENERAL: single byte, lower 5 bits = length
            byteArrayOf(totalLength.toByte())
        }
        totalLength <= 8191 -> {
            // EXT_13: 2 bytes
            val byte0 = (0x20 or (totalLength shr 8)).toByte()
            val byte1 = (totalLength and 0xFF).toByte()
            byteArrayOf(byte0, byte1)
        }
        else -> {
            // EXT_16: 3 bytes
            val byte0 = 0x40.toByte()
            val byte1 = (totalLength shr 8).toByte()
            val byte2 = (totalLength and 0xFF).toByte()
            byteArrayOf(byte0, byte1, byte2)
        }
    }

    // --- RX: Assemble fragmented notifications into complete responses ---

    /**
     * Merge function for Nordic BLE `mergeIndexed()` operator.
     * Accumulator format: [totalLength (4 bytes big-endian)] [payload bytes...]
     *
     * On first packet (index == 0): parse header, extract total length, start accumulating.
     * On continuation packets: strip 0x80 prefix, append to accumulator.
     * Returns MergeResult.Completed when all bytes received.
     */
    val goProMerge: suspend (ByteArray, ByteArray, Int) -> MergeResult = { acc, received, index ->
        if (index == 0) {
            // First packet: parse header to determine total payload length
            val byte0 = received[0].toInt() and 0xFF
            val headerType = (byte0 shr 5) and 0x03

            val (totalLength, headerSize) = when (headerType) {
                0b00 -> Pair(byte0 and 0x1F, 1) // GENERAL
                0b01 -> Pair(
                    ((byte0 and 0x1F) shl 8) or (received[1].toInt() and 0xFF),
                    2
                ) // EXT_13
                0b10 -> Pair(
                    ((received[1].toInt() and 0xFF) shl 8) or (received[2].toInt() and 0xFF),
                    3
                ) // EXT_16
                else -> throw IllegalArgumentException("Reserved header type: $headerType")
            }

            val payload = received.copyOfRange(headerSize, received.size)

            if (payload.size >= totalLength) {
                // Single packet, complete
                MergeResult.Completed(payload.copyOfRange(0, totalLength))
            } else {
                // Multi-packet: store totalLength in 4-byte prefix
                val prefix = byteArrayOf(
                    (totalLength shr 24).toByte(),
                    (totalLength shr 16).toByte(),
                    (totalLength shr 8).toByte(),
                    totalLength.toByte()
                )
                MergeResult.Accumulate(prefix + payload)
            }
        } else {
            // Continuation packet: drop 0x80 header byte, append to accumulator
            val chunk = received.copyOfRange(1, received.size)
            val newAcc = acc + chunk

            // Extract total length from 4-byte prefix
            val totalLength = ((newAcc[0].toInt() and 0xFF) shl 24) or
                    ((newAcc[1].toInt() and 0xFF) shl 16) or
                    ((newAcc[2].toInt() and 0xFF) shl 8) or
                    (newAcc[3].toInt() and 0xFF)
            val payloadSoFar = newAcc.size - 4

            if (payloadSoFar >= totalLength) {
                // All data received, strip the 4-byte prefix
                MergeResult.Completed(newAcc.copyOfRange(4, 4 + totalLength))
            } else {
                MergeResult.Accumulate(newAcc)
            }
        }
    }

    // --- Response parsing ---

    /**
     * Parse assembled payload into a BleResponse based on source characteristic.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun parseResponse(sourceUuid: Uuid, payload: ByteArray): BleResponse {
        if (payload.isEmpty()) return BleResponse.Unknown(payload)

        return when (sourceUuid) {
            GoProUuid.CQ_COMMAND_RSP -> parseCommandResponse(payload)
            GoProUuid.CQ_SETTING_RSP -> parseSettingResponse(payload)
            GoProUuid.CQ_QUERY_RSP -> parseQueryResponse(payload)
            else -> BleResponse.Unknown(payload)
        }
    }

    private fun parseCommandResponse(payload: ByteArray): BleResponse {
        if (payload.size < 2) return BleResponse.Unknown(payload)

        val firstByte = payload[0].toInt() and 0xFF

        // Protobuf response: featureId is 0xF1, 0xF3, or 0xF5
        if (firstByte in setOf(0xF1, 0xF3, 0xF5) && payload.size >= 3) {
            return BleResponse.Protobuf(
                featureId = payload[0],
                actionId = payload[1],
                status = payload[2],
                payload = if (payload.size > 3) payload.copyOfRange(3, payload.size) else byteArrayOf()
            )
        }

        // TLV command response: [commandId, status, payload...]
        return BleResponse.Command(
            commandId = payload[0],
            status = payload[1],
            payload = if (payload.size > 2) payload.copyOfRange(2, payload.size) else byteArrayOf()
        )
    }

    private fun parseSettingResponse(payload: ByteArray): BleResponse {
        if (payload.size < 2) return BleResponse.Unknown(payload)
        return BleResponse.Setting(
            settingId = payload[0],
            status = payload[1]
        )
    }

    private fun parseQueryResponse(payload: ByteArray): BleResponse {
        if (payload.size < 2) return BleResponse.Unknown(payload)
        val queryId = payload[0]
        val status = payload[1]
        val tlvData = if (payload.size > 2) payload.copyOfRange(2, payload.size) else byteArrayOf()
        return BleResponse.Query(
            queryId = queryId,
            status = status,
            statusMap = parseTlvMap(tlvData)
        )
    }

    /**
     * Parse TLV map: [id, length, value..., id, length, value...]
     */
    fun parseTlvMap(data: ByteArray): Map<Byte, ByteArray> {
        val map = mutableMapOf<Byte, ByteArray>()
        var i = 0
        while (i + 1 < data.size) {
            val id = data[i++]
            val len = data[i++].toInt() and 0xFF
            if (i + len > data.size) break
            map[id] = data.copyOfRange(i, i + len)
            i += len
        }
        return map
    }
}

/**
 * Parsed BLE response from GoPro camera.
 */
sealed class BleResponse {
    /** TLV command response from CQ_COMMAND_RSP */
    data class Command(
        val commandId: Byte,
        val status: Byte,
        val payload: ByteArray
    ) : BleResponse() {
        val isSuccess: Boolean get() = status == 0.toByte()
    }

    /** Setting response from CQ_SETTING_RSP */
    data class Setting(
        val settingId: Byte,
        val status: Byte
    ) : BleResponse() {
        val isSuccess: Boolean get() = status == 0.toByte()
    }

    /** Query response from CQ_QUERY_RSP with TLV status map */
    data class Query(
        val queryId: Byte,
        val status: Byte,
        val statusMap: Map<Byte, ByteArray>
    ) : BleResponse() {
        val isSuccess: Boolean get() = status == 0.toByte()
    }

    /** Protobuf response (SetCameraControl, etc.) */
    data class Protobuf(
        val featureId: Byte,
        val actionId: Byte,
        val status: Byte,
        val payload: ByteArray
    ) : BleResponse() {
        val isSuccess: Boolean get() = status == 0.toByte()
    }

    /** Unknown or unparseable response */
    data class Unknown(val raw: ByteArray) : BleResponse()
}
