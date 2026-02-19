package com.clipride.karoo

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipRidePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    // --- Auto-Record ---

    var autoRecordEnabled: Boolean
        get() = prefs.getBoolean("auto_record", true)
        set(value) = prefs.edit().putBoolean("auto_record", value).apply()

    var autoRecordDelaySeconds: Int
        get() = prefs.getString("auto_record_delay", "0")?.toIntOrNull() ?: 0
        set(value) = prefs.edit().putString("auto_record_delay", value.toString()).apply()

    var continueOnAutoPause: Boolean
        get() = prefs.getBoolean("continue_on_auto_pause", true)
        set(value) = prefs.edit().putBoolean("continue_on_auto_pause", value).apply()

    // --- Auto-Highlights ---

    var highlightOnLap: Boolean
        get() = prefs.getBoolean("highlight_on_lap", false)
        set(value) = prefs.edit().putBoolean("highlight_on_lap", value).apply()

    var highlightOnPeakPower: Boolean
        get() = prefs.getBoolean("highlight_on_peak_power", false)
        set(value) = prefs.edit().putBoolean("highlight_on_peak_power", value).apply()

    var peakPowerThreshold: Int
        get() = prefs.getString("peak_power_threshold", "500")?.toIntOrNull() ?: 500
        set(value) = prefs.edit().putString("peak_power_threshold", value.toString()).apply()

    var highlightOnMaxSpeed: Boolean
        get() = prefs.getBoolean("highlight_on_max_speed", false)
        set(value) = prefs.edit().putBoolean("highlight_on_max_speed", value).apply()

    var maxSpeedThreshold: Int
        get() = prefs.getString("max_speed_threshold", "50")?.toIntOrNull() ?: 50
        set(value) = prefs.edit().putString("max_speed_threshold", value.toString()).apply()

    // --- Battery Alerts ---

    var batteryLowThreshold: Int
        get() = prefs.getString("battery_alert_low", "20")?.toIntOrNull() ?: 20
        set(value) = prefs.edit().putString("battery_alert_low", value.toString()).apply()

    var batteryCriticalThreshold: Int
        get() = prefs.getString("battery_alert_critical", "10")?.toIntOrNull() ?: 10
        set(value) = prefs.edit().putString("battery_alert_critical", value.toString()).apply()

    // --- Saved Device ---

    val savedDeviceAddress: String?
        get() = prefs.getString("saved_device_address", null)

    val savedDeviceName: String?
        get() = prefs.getString("saved_device_name", null)

    fun saveDevice(address: String, name: String?) {
        prefs.edit()
            .putString("saved_device_address", address)
            .putString("saved_device_name", name)
            .apply()
    }

    fun forgetDevice() {
        prefs.edit()
            .remove("saved_device_address")
            .remove("saved_device_name")
            .apply()
    }

    fun resetToDefaults() {
        val address = savedDeviceAddress
        val name = savedDeviceName
        prefs.edit().clear().apply()
        // Restore paired device — reset settings, not pairing
        if (address != null) {
            saveDevice(address, name)
        }
    }
}
