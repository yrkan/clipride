package com.clipride.ble

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import timber.log.Timber

/**
 * Listens for Bluetooth pairing notifications and auto-confirms them.
 *
 * On Android 12+, BLE pairing requests are shown as notifications with
 * "Pair & connect" action (instead of a dialog). On Karoo 3, these
 * notifications are invisible (no notification shade).
 *
 * This service detects the pairing notification and triggers the
 * "Pair & connect" PendingIntent, which runs in the Settings app's
 * context (UID 1000, has BLUETOOTH_PRIVILEGED).
 *
 * Requires notification listener permission, granted via ADB:
 *   adb shell cmd notification allow_listener com.clipride/com.clipride.ble.PairingNotificationListener
 */
class PairingNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: PairingNotificationListener? = null

        /**
         * Check if NotificationListener is active (service connected).
         */
        fun isEnabled(): Boolean = instance != null

        /**
         * Attempt to auto-confirm any pending Bluetooth pairing notification.
         * Called from GoProBleManager when ACTION_PAIRING_REQUEST is received.
         * Returns true if a pairing notification was found and confirmed.
         */
        fun tryAutoConfirmPairing(): Boolean {
            val listener = instance
            if (listener == null) {
                Timber.w("PairingListener: service not connected")
                return false
            }
            return listener.checkAndConfirmPairing()
        }
    }

    override fun onListenerConnected() {
        Timber.d("PairingListener: connected")
        instance = this
        checkAndConfirmPairing()
    }

    override fun onListenerDisconnected() {
        Timber.d("PairingListener: disconnected")
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isBluetoothPairingNotification(sbn)) {
            Timber.d("PairingListener: BT pairing notification posted")
            handlePairingNotification(sbn)
        }
    }

    private fun isBluetoothPairingNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != "com.android.settings") return false
        val channelId = sbn.notification?.channelId ?: return false
        return channelId == "bluetooth_notification_channel"
    }

    fun checkAndConfirmPairing(): Boolean {
        try {
            val active = activeNotifications ?: return false
            for (sbn in active) {
                if (isBluetoothPairingNotification(sbn)) {
                    return handlePairingNotification(sbn)
                }
            }
            Timber.d("PairingListener: no BT pairing notification in ${active.size} active")
        } catch (e: Exception) {
            Timber.e(e, "PairingListener: error checking notifications")
        }
        return false
    }

    private fun handlePairingNotification(sbn: StatusBarNotification): Boolean {
        val actions = sbn.notification?.actions
        if (actions == null || actions.isEmpty()) {
            Timber.w("PairingListener: notification has no actions")
            return false
        }

        val pairAction = actions[0]
        val actionTitle = pairAction.title?.toString() ?: ""
        Timber.d("PairingListener: confirming '$actionTitle'")

        return try {
            pairAction.actionIntent.send()
            Timber.d("PairingListener: triggered '$actionTitle' — pairing should complete")
            cancelNotification(sbn.key)
            true
        } catch (e: Exception) {
            Timber.e(e, "PairingListener: failed to send PendingIntent")
            false
        }
    }
}
