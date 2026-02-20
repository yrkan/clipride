package com.clipride.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.clipride.R
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProCommands
import com.clipride.ble.GoProConnectionState
import com.clipride.karoo.ClipRidePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.clipride.ui.components.ConfirmDialog
import com.clipride.ui.components.SettingsActionRow
import com.clipride.ui.components.SettingsPickerRow
import com.clipride.ui.components.SettingsSection
import com.clipride.ui.components.SettingsStatusCard
import com.clipride.ui.components.SettingsSwitchRow
import com.clipride.ui.theme.BatteryCritical
import com.clipride.ui.theme.BatteryGood
import com.clipride.ui.theme.BatteryLow
import com.clipride.ui.theme.ClipRideTheme
import com.clipride.ui.theme.Spacing
import com.clipride.ui.theme.StatusConnected
import com.clipride.ui.theme.StatusConnecting
import com.clipride.ui.theme.StatusDisconnected
import com.clipride.ui.theme.StatusError
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    @Inject lateinit var preferences: ClipRidePreferences
    @Inject lateinit var bleManager: GoProBleManager
    @Inject lateinit var commands: GoProCommands

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipRideTheme {
                SettingsScreen(
                    preferences = preferences,
                    bleManager = bleManager,
                    onPairCamera = {
                        startActivity(Intent(this, PairingActivity::class.java))
                    },
                    onReconnect = {
                        val addr = preferences.savedDeviceAddress ?: return@SettingsScreen
                        bleManager.startConnection(addr)
                    },
                    onTurnOff = {
                        CoroutineScope(Dispatchers.IO).launch {
                            try { commands.sleep() } catch (e: Exception) {
                                Timber.w(e, "Sleep command failed, disconnecting anyway")
                            }
                            bleManager.stopConnection()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    preferences: ClipRidePreferences,
    bleManager: GoProBleManager,
    onPairCamera: () -> Unit,
    onReconnect: () -> Unit,
    onTurnOff: () -> Unit,
) {
    val connectionState by bleManager.connectionState.collectAsState()
    val batteryLevel by bleManager.batteryLevel.collectAsState()

    var autoRecord by remember { mutableStateOf(preferences.autoRecordEnabled) }
    var autoRecordDelay by remember { mutableStateOf(preferences.autoRecordDelaySeconds) }
    var continueOnAutoPause by remember { mutableStateOf(preferences.continueOnAutoPause) }
    var highlightLap by remember { mutableStateOf(preferences.highlightOnLap) }
    var highlightPower by remember { mutableStateOf(preferences.highlightOnPeakPower) }
    var powerThreshold by remember { mutableStateOf(preferences.peakPowerThreshold) }
    var highlightSpeed by remember { mutableStateOf(preferences.highlightOnMaxSpeed) }
    var speedThreshold by remember { mutableStateOf(preferences.maxSpeedThreshold) }
    var videoResolution by remember { mutableStateOf(preferences.videoResolution) }
    var videoFps by remember { mutableStateOf(preferences.videoFps) }
    var videoFov by remember { mutableStateOf(preferences.videoFov) }
    var videoHyperSmooth by remember { mutableStateOf(preferences.videoHyperSmooth) }
    var highlightClimb by remember { mutableStateOf(preferences.highlightOnClimbSummit) }
    var highlightHrZone5 by remember { mutableStateOf(preferences.highlightOnHrZone5) }
    var highlightDescent by remember { mutableStateOf(preferences.highlightOnDescent) }
    var descentThreshold by remember { mutableStateOf(preferences.descentSpeedThreshold) }
    var highlightBookmark by remember { mutableStateOf(preferences.highlightOnRideBookmark) }
    var batteryLow by remember { mutableStateOf(preferences.batteryLowThreshold) }
    var batteryCritical by remember { mutableStateOf(preferences.batteryCriticalThreshold) }

    var showForgetDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    var refreshKey by remember { mutableIntStateOf(0) }
    val deviceAddress = remember(refreshKey) { preferences.savedDeviceAddress }
    val deviceName = remember(refreshKey) { preferences.savedDeviceName }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshKey++
    }

    fun reloadPreferences() {
        autoRecord = preferences.autoRecordEnabled
        autoRecordDelay = preferences.autoRecordDelaySeconds
        continueOnAutoPause = preferences.continueOnAutoPause
        highlightLap = preferences.highlightOnLap
        highlightPower = preferences.highlightOnPeakPower
        powerThreshold = preferences.peakPowerThreshold
        highlightSpeed = preferences.highlightOnMaxSpeed
        speedThreshold = preferences.maxSpeedThreshold
        videoResolution = preferences.videoResolution
        videoFps = preferences.videoFps
        videoFov = preferences.videoFov
        videoHyperSmooth = preferences.videoHyperSmooth
        highlightClimb = preferences.highlightOnClimbSummit
        highlightHrZone5 = preferences.highlightOnHrZone5
        highlightDescent = preferences.highlightOnDescent
        descentThreshold = preferences.descentSpeedThreshold
        highlightBookmark = preferences.highlightOnRideBookmark
        batteryLow = preferences.batteryLowThreshold
        batteryCritical = preferences.batteryCriticalThreshold
        refreshKey++
    }

    val delayLabels = listOf(
        stringResource(R.string.settings_none),
        stringResource(R.string.delay_5s),
        stringResource(R.string.delay_10s),
        stringResource(R.string.delay_15s),
    )
    val delayValues = listOf(0, 5, 10, 15)
    val powerOptions = listOf(300, 400, 500, 600, 700, 800, 1000)
    val speedOptions = listOf(30, 40, 50, 60, 70, 80)
    val descentSpeedOptions = listOf(40, 50, 60, 70, 80)
    val noChange = stringResource(R.string.pref_video_no_change)
    val resolutionLabels = listOf(noChange, "1080p", "2.7K", "4K", "5.3K")
    val resolutionValues = listOf(-1, 9, 4, 1, 18)
    val fpsLabels = listOf(noChange, "24", "30", "60", "120")
    val fpsValues = listOf(-1, 10, 8, 5, 1)
    val fovLabels = listOf(noChange, "Wide", "Linear", "Narrow", "SuperView")
    val fovValues = listOf(-1, 0, 4, 2, 3)
    val hsLabels = listOf(noChange, "Off", "On", "High", "Boost", "Auto Boost")
    val hsValues = listOf(-1, 0, 1, 2, 3, 4)
    val batteryLowOptions = listOf(15, 20, 25, 30)
    val batteryCriticalOptions = listOf(5, 10, 15)

    val isConnected = connectionState == GoProConnectionState.CONNECTED
    val isConnecting = connectionState == GoProConnectionState.CONNECTING
            || connectionState == GoProConnectionState.SCANNING
            || connectionState == GoProConnectionState.PAIRING

    val dotColor = when {
        isConnected -> StatusConnected
        isConnecting -> StatusConnecting
        else -> StatusDisconnected
    }
    val statusText = when (connectionState) {
        GoProConnectionState.CONNECTED -> stringResource(R.string.status_connected)
        GoProConnectionState.CONNECTING -> stringResource(R.string.status_connecting)
        GoProConnectionState.SCANNING -> stringResource(R.string.status_scanning)
        GoProConnectionState.PAIRING -> stringResource(R.string.status_pairing)
        GoProConnectionState.DISCONNECTED -> stringResource(R.string.status_disconnected)
    }
    val batColor = when {
        batteryLevel == null -> StatusDisconnected
        batteryLevel!! < 15 -> BatteryCritical
        batteryLevel!! < 30 -> BatteryLow
        else -> BatteryGood
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        // Status Card
        SettingsStatusCard(
            dotColor = dotColor,
            statusText = statusText,
            batteryLevel = if (isConnected) batteryLevel else null,
            batteryColor = batColor,
            deviceName = deviceName,
        )

        Spacer(Modifier.height(Spacing.md))

        // Camera section
        SettingsSection(stringResource(R.string.pref_cat_camera)) {
            when {
                // No saved device → show "Connect Camera"
                deviceAddress == null -> {
                    Button(
                        onClick = onPairCamera,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(vertical = Spacing.sm),
                    ) {
                        Text(stringResource(R.string.settings_connect_camera), fontSize = 13.sp)
                    }
                }

                // Connected → show "Turn Off" + "Forget"
                isConnected -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        OutlinedButton(
                            onClick = onTurnOff,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(vertical = Spacing.sm),
                        ) {
                            Text(stringResource(R.string.settings_turn_off), fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { showForgetDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(vertical = Spacing.sm),
                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                        ) {
                            Text(
                                stringResource(R.string.settings_forget),
                                fontSize = 13.sp,
                                color = StatusError.copy(alpha = 0.8f),
                            )
                        }
                    }
                }

                // Connecting/scanning/pairing → show only "Forget" (connection in progress)
                isConnecting -> {
                    OutlinedButton(
                        onClick = { showForgetDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(vertical = Spacing.sm),
                        border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            stringResource(R.string.settings_forget),
                            fontSize = 13.sp,
                            color = StatusError.copy(alpha = 0.8f),
                        )
                    }
                }

                // Disconnected with saved device → show "Reconnect" + "Forget"
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Button(
                            onClick = onReconnect,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(vertical = Spacing.sm),
                        ) {
                            Text(stringResource(R.string.settings_reconnect), fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { showForgetDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(vertical = Spacing.sm),
                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                        ) {
                            Text(
                                stringResource(R.string.settings_forget),
                                fontSize = 13.sp,
                                color = StatusError.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        // Recording section
        SettingsSection(stringResource(R.string.pref_cat_recording)) {
            SettingsSwitchRow(
                title = stringResource(R.string.pref_auto_record),
                checked = autoRecord,
                onCheckedChange = {
                    autoRecord = it
                    preferences.autoRecordEnabled = it
                },
            )
            SettingsPickerRow(
                title = stringResource(R.string.pref_auto_record_delay),
                options = delayLabels,
                values = delayValues,
                selectedValue = autoRecordDelay,
                onSelect = {
                    autoRecordDelay = it
                    preferences.autoRecordDelaySeconds = it
                },
                enabled = autoRecord,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.pref_continue_auto_pause),
                checked = continueOnAutoPause,
                onCheckedChange = {
                    continueOnAutoPause = it
                    preferences.continueOnAutoPause = it
                },
                enabled = autoRecord,
            )
        }

        Spacer(Modifier.height(Spacing.md))

        // Video settings section
        SettingsSection(stringResource(R.string.pref_cat_video)) {
            SettingsPickerRow(
                title = stringResource(R.string.pref_video_resolution),
                options = resolutionLabels,
                values = resolutionValues,
                selectedValue = videoResolution,
                onSelect = {
                    videoResolution = it
                    preferences.videoResolution = it
                },
            )
            SettingsPickerRow(
                title = stringResource(R.string.pref_video_fps),
                options = fpsLabels,
                values = fpsValues,
                selectedValue = videoFps,
                onSelect = {
                    videoFps = it
                    preferences.videoFps = it
                },
            )
            SettingsPickerRow(
                title = stringResource(R.string.pref_video_fov),
                options = fovLabels,
                values = fovValues,
                selectedValue = videoFov,
                onSelect = {
                    videoFov = it
                    preferences.videoFov = it
                },
            )
            SettingsPickerRow(
                title = stringResource(R.string.pref_video_hypersmooth),
                options = hsLabels,
                values = hsValues,
                selectedValue = videoHyperSmooth,
                onSelect = {
                    videoHyperSmooth = it
                    preferences.videoHyperSmooth = it
                },
            )
        }

        Spacer(Modifier.height(Spacing.md))

        // Highlights section
        SettingsSection(stringResource(R.string.pref_cat_highlights)) {
            SettingsSwitchRow(
                title = stringResource(R.string.pref_highlight_lap),
                checked = highlightLap,
                onCheckedChange = {
                    highlightLap = it
                    preferences.highlightOnLap = it
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.pref_highlight_power),
                checked = highlightPower,
                onCheckedChange = {
                    highlightPower = it
                    preferences.highlightOnPeakPower = it
                },
            )
            SettingsPickerRow(
                title = stringResource(R.string.pref_power_threshold),
                options = powerOptions.map { "${it}W" },
                values = powerOptions,
                selectedValue = powerThreshold,
                onSelect = {
                    powerThreshold = it
                    preferences.peakPowerThreshold = it
                },
                enabled = highlightPower,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.pref_highlight_speed),
                checked = highlightSpeed,
                onCheckedChange = {
                    highlightSpeed = it
                    preferences.highlightOnMaxSpeed = it
                },
            )
            SettingsPickerRow(
                title = stringResource(R.string.pref_speed_threshold),
                options = speedOptions.map { "${it} km/h" },
                values = speedOptions,
                selectedValue = speedThreshold,
                onSelect = {
                    speedThreshold = it
                    preferences.maxSpeedThreshold = it
                },
                enabled = highlightSpeed,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.pref_highlight_climb),
                checked = highlightClimb,
                onCheckedChange = {
                    highlightClimb = it
                    preferences.highlightOnClimbSummit = it
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.pref_highlight_hr_zone5),
                checked = highlightHrZone5,
                onCheckedChange = {
                    highlightHrZone5 = it
                    preferences.highlightOnHrZone5 = it
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.pref_highlight_descent),
                checked = highlightDescent,
                onCheckedChange = {
                    highlightDescent = it
                    preferences.highlightOnDescent = it
                },
            )
            SettingsPickerRow(
                title = stringResource(R.string.pref_descent_threshold),
                options = descentSpeedOptions.map { "${it} km/h" },
                values = descentSpeedOptions,
                selectedValue = descentThreshold,
                onSelect = {
                    descentThreshold = it
                    preferences.descentSpeedThreshold = it
                },
                enabled = highlightDescent,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.pref_highlight_bookmark),
                checked = highlightBookmark,
                onCheckedChange = {
                    highlightBookmark = it
                    preferences.highlightOnRideBookmark = it
                },
            )
        }

        Spacer(Modifier.height(Spacing.md))

        // Alerts section
        SettingsSection(stringResource(R.string.pref_cat_alerts)) {
            SettingsPickerRow(
                title = stringResource(R.string.pref_battery_low),
                options = batteryLowOptions.map { "${it}%" },
                values = batteryLowOptions,
                selectedValue = batteryLow,
                onSelect = {
                    batteryLow = it
                    preferences.batteryLowThreshold = it
                },
            )
            SettingsPickerRow(
                title = stringResource(R.string.pref_battery_critical),
                options = batteryCriticalOptions.map { "${it}%" },
                values = batteryCriticalOptions,
                selectedValue = batteryCritical,
                onSelect = {
                    batteryCritical = it
                    preferences.batteryCriticalThreshold = it
                },
            )
        }

        Spacer(Modifier.height(Spacing.md))

        // Advanced section
        SettingsSection(stringResource(R.string.pref_cat_advanced)) {
            SettingsActionRow(
                title = stringResource(R.string.pref_reset),
                onClick = { showResetDialog = true },
                destructive = true,
            )
        }

        // Disclaimer
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.trademark_disclaimer),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            lineHeight = 13.sp,
        )
        Spacer(Modifier.height(Spacing.md))
    }

    // Dialogs

    if (showForgetDialog) {
        ConfirmDialog(
            title = stringResource(R.string.pref_forget_confirm_title),
            message = stringResource(R.string.pref_forget_confirm_msg),
            onConfirm = {
                preferences.forgetDevice()
                refreshKey++
                showForgetDialog = false
            },
            onDismiss = { showForgetDialog = false },
        )
    }

    if (showResetDialog) {
        ConfirmDialog(
            title = stringResource(R.string.pref_reset_confirm_title),
            message = stringResource(R.string.pref_reset_confirm_msg),
            onConfirm = {
                preferences.resetToDefaults()
                reloadPreferences()
                showResetDialog = false
            },
            onDismiss = { showResetDialog = false },
        )
    }
}
