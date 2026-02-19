package com.clipride.ble

import no.nordicsemi.kotlin.ble.client.android.exception.PeripheralClosedException
import no.nordicsemi.kotlin.ble.client.android.exception.BondingFailedException
import no.nordicsemi.kotlin.ble.client.exception.ConnectionFailedException

/**
 * Maps BLE exceptions to user-friendly error messages.
 */
object ErrorMessages {

    data class UserError(
        val title: String,
        val detail: String,
        val recoverable: Boolean
    )

    fun fromException(e: Throwable): UserError {
        return when (e) {
            is PeripheralClosedException -> UserError(
                title = "Camera Disconnected",
                detail = "Camera connection lost. Make sure it is on and nearby.",
                recoverable = true
            )
            is BondingFailedException -> UserError(
                title = "Pairing Failed",
                detail = "Could not pair. Remove the camera from Bluetooth list and try again.",
                recoverable = true
            )
            is ConnectionFailedException -> UserError(
                title = "Connection Failed",
                detail = "Could not connect. Try restarting the camera or removing it from Bluetooth settings.",
                recoverable = true
            )
            is SecurityException -> UserError(
                title = "Bluetooth Permission Required",
                detail = "Grant Bluetooth permission in settings.",
                recoverable = false
            )
            is IllegalStateException -> {
                if (e.message?.contains("not found") == true) {
                    UserError(
                        title = "Camera Not Found",
                        detail = "Camera not detected. Make sure it is on and BLE is active.",
                        recoverable = true
                    )
                } else {
                    UserError(
                        title = "Connection Error",
                        detail = e.message ?: "An unknown error occurred.",
                        recoverable = true
                    )
                }
            }
            else -> {
                val message = e.message ?: ""
                when {
                    message.contains("timeout", ignoreCase = true) -> UserError(
                        title = "Connection Timeout",
                        detail = "Camera not responding. Make sure it is on and in range.",
                        recoverable = true
                    )
                    message.contains("GATT", ignoreCase = true) -> UserError(
                        title = "BLE Error",
                        detail = "Bluetooth connection error. Try restarting the camera.",
                        recoverable = true
                    )
                    else -> UserError(
                        title = "Connection Error",
                        detail = message.ifEmpty { "An unknown error occurred." },
                        recoverable = true
                    )
                }
            }
        }
    }
}
