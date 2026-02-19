package com.clipride.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.os.Bundle
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipride.R
import com.clipride.ble.BluetoothPermissions
import com.clipride.ble.BondingRequiredException
import com.clipride.ble.GoProBleManager
import com.clipride.ble.GoProConnectionState
import com.clipride.ble.PairingNotificationListener
import com.clipride.karoo.ClipRidePreferences
import com.clipride.ui.theme.ClipRideTheme
import com.clipride.ui.theme.Spacing
import com.clipride.ui.theme.StatusConnected
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

private data class ScannedDevice(val address: String, val name: String)

private sealed class PairingState {
    data object Idle : PairingState()
    data object Scanning : PairingState()
    data class Associating(val device: ScannedDevice) : PairingState()
    data class Connecting(val device: ScannedDevice) : PairingState()
    data class Connected(val device: ScannedDevice) : PairingState()
    data class ManualPairingNeeded(val device: ScannedDevice) : PairingState()
    data class Error(val message: String) : PairingState()
}

@AndroidEntryPoint
class PairingActivity : ComponentActivity() {

    @Inject lateinit var bleManager: GoProBleManager
    @Inject lateinit var preferences: ClipRidePreferences

    private val pairingState = mutableStateOf<PairingState>(PairingState.Idle)
    private val scannedDevices = mutableStateListOf<ScannedDevice>()

    private lateinit var cdmLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cdmLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result -> handleCdmResult(result) }

        val hasPermissions = mutableStateOf(BluetoothPermissions.hasPermissions(this))

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            hasPermissions.value = results.values.all { it }
        }

        if (!hasPermissions.value) {
            permissionLauncher.launch(BluetoothPermissions.requiredPermissions())
        }

        setContent {
            ClipRideTheme {
                PairingScreen(
                    bleManager = bleManager,
                    preferences = preferences,
                    hasPermissions = hasPermissions.value,
                    state = pairingState.value,
                    devices = scannedDevices,
                    onStateChange = { pairingState.value = it },
                    onConnect = { device -> startCdmAssociation(device) },
                    onFinish = { finish() },
                    onOpenListenerSettings = { openNotificationListenerSettings() },
                )
            }
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "Could not open notification listener settings")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCdmAssociation(device: ScannedDevice) {
        if (!PairingNotificationListener.isEnabled()) {
            Timber.d("CDM: NotificationListener not enabled — showing setup screen")
            pairingState.value = PairingState.ManualPairingNeeded(device)
            return
        }

        pairingState.value = PairingState.Associating(device)

        val deviceManager = getSystemService(CompanionDeviceManager::class.java)

        val scanFilter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString("0000fea6-0000-1000-8000-00805f9b34fb"))
            .setDeviceAddress(device.address)
            .build()

        val bleFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(scanFilter)
            .build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(bleFilter)
            .setSingleDevice(true)
            .build()

        Timber.d("CDM: starting association for ${device.name} (${device.address})")

        @Suppress("DEPRECATION")
        deviceManager.associate(request, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                Timber.d("CDM: device found, launching system chooser dialog")
                cdmLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
            }

            override fun onFailure(error: CharSequence?) {
                Timber.w("CDM: association failed: $error")
                pairingState.value = PairingState.Error(
                    "System association failed: $error"
                )
            }
        }, null)
    }

    @SuppressLint("MissingPermission")
    private fun handleCdmResult(result: ActivityResult) {
        if (result.resultCode == RESULT_OK && result.data != null) {
            @Suppress("DEPRECATION")
            val scanResult = result.data?.getParcelableExtra<android.bluetooth.le.ScanResult>(
                CompanionDeviceManager.EXTRA_DEVICE
            )
            @Suppress("DEPRECATION")
            val btDevice = scanResult?.device
                ?: result.data?.getParcelableExtra<BluetoothDevice>(
                    CompanionDeviceManager.EXTRA_DEVICE
                )

            if (btDevice != null) {
                Timber.d("CDM: associated ${btDevice.name} (${btDevice.address})")
                val device = ScannedDevice(btDevice.address, btDevice.name ?: "Camera")
                pairingState.value = PairingState.Connecting(device)
            } else {
                Timber.w("CDM: no device in result")
                pairingState.value = PairingState.Error("No device returned")
            }
        } else {
            Timber.d("CDM: cancelled (resultCode=${result.resultCode})")
            if (scannedDevices.isNotEmpty()) {
                pairingState.value = PairingState.Scanning
            } else {
                pairingState.value = PairingState.Error("Association cancelled")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingScreen(
    bleManager: GoProBleManager,
    preferences: ClipRidePreferences,
    hasPermissions: Boolean,
    state: PairingState,
    devices: MutableList<ScannedDevice>,
    onStateChange: (PairingState) -> Unit,
    onConnect: (ScannedDevice) -> Unit,
    onFinish: () -> Unit,
    onOpenListenerSettings: () -> Unit,
) {
    var scanTrigger by remember { mutableStateOf(0) }
    var selectedDevice by remember { mutableStateOf<ScannedDevice?>(null) }
    var isScanning by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions && state is PairingState.Idle) {
            scanTrigger++
        }
    }

    LaunchedEffect(devices.size) {
        if (devices.isNotEmpty() && selectedDevice == null) {
            selectedDevice = devices.first()
        }
    }

    LaunchedEffect(scanTrigger) {
        if (scanTrigger == 0 || !hasPermissions) return@LaunchedEffect
        onStateChange(PairingState.Scanning)
        devices.clear()
        selectedDevice = null
        isScanning = true

        try {
            bleManager.scan()
                .catch { e -> Timber.w(e, "Scan error") }
                .collect { (address, name) ->
                    if (state !is PairingState.Scanning) return@collect
                    val displayName = name ?: "Camera ($address)"
                    if (devices.none { it.address == address }) {
                        devices.add(ScannedDevice(address, displayName))
                    }
                }
            isScanning = false
            if (state is PairingState.Scanning && devices.isEmpty()) {
                onStateChange(PairingState.Error("No cameras found. Make sure the camera is turned on"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            isScanning = false
            Timber.w(e, "Scan failed")
            if (state is PairingState.Scanning) {
                onStateChange(PairingState.Error("Scan failed: ${e.message}"))
            }
        }
    }

    val connectingDevice = (state as? PairingState.Connecting)?.device
    LaunchedEffect(connectingDevice) {
        val device = connectingDevice ?: return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                bleManager.connectForPairing(device.address)
            }
            preferences.saveDevice(device.address, device.name)
            onStateChange(PairingState.Connected(device))
        } catch (e: BondingRequiredException) {
            Timber.d("Bonding required for ${e.address}")
            onStateChange(PairingState.ManualPairingNeeded(device))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Connection failed")
            onStateChange(PairingState.Error("Connection failed: ${e.message ?: "Unknown error"}"))
        }
    }

    if (state is PairingState.Connected) {
        LaunchedEffect(Unit) {
            delay(800)
            onFinish()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_title)) },
                navigationIcon = {
                    IconButton(onClick = onFinish) {
                        Text(
                            text = "\u2715",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when (val currentState = state) {
                    is PairingState.Idle -> {
                        if (!hasPermissions) {
                            CenteredMessage(stringResource(R.string.pairing_bt_permission_required))
                        }
                    }

                    is PairingState.Scanning -> {
                        if (devices.isEmpty()) {
                            ScanningView()
                        } else {
                            DeviceList(
                                devices = devices,
                                selectedDevice = selectedDevice,
                                isScanning = isScanning,
                                onSelect = { selectedDevice = it },
                                onScanAgain = { scanTrigger++ },
                            )
                        }
                    }

                    is PairingState.Associating -> {
                        ProgressView(
                            stringResource(R.string.pairing_associating),
                            currentState.device.name,
                        )
                    }

                    is PairingState.Connecting -> {
                        ConnectingView(currentState.device, bleManager)
                    }

                    is PairingState.ManualPairingNeeded -> {
                        ManualPairingView(
                            device = currentState.device,
                            onRetry = { onStateChange(PairingState.Connecting(currentState.device)) },
                            onCancel = onFinish,
                            onOpenListenerSettings = onOpenListenerSettings,
                        )
                    }

                    is PairingState.Connected -> {
                        ConnectedView(currentState.device)
                    }

                    is PairingState.Error -> {
                        if (devices.isNotEmpty()) {
                            DeviceList(
                                devices = devices,
                                selectedDevice = selectedDevice,
                                isScanning = false,
                                onSelect = { selectedDevice = it },
                                onScanAgain = { scanTrigger++ },
                            )
                        } else {
                            ErrorView(
                                message = currentState.message,
                                onRetry = { scanTrigger++ },
                                onCancel = onFinish,
                            )
                        }
                    }
                }
            }

            val showConnect = (state is PairingState.Scanning || state is PairingState.Error) &&
                selectedDevice != null && devices.isNotEmpty()
            if (showConnect) {
                Button(
                    onClick = { selectedDevice?.let { onConnect(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(stringResource(R.string.pairing_connect), fontSize = 16.sp)
                }
            }
        }
    }
}

// --- UI Components ---

@Composable
private fun ScanningView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.pairing_searching),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceList(
    devices: List<ScannedDevice>,
    selectedDevice: ScannedDevice?,
    isScanning: Boolean,
    onSelect: (ScannedDevice) -> Unit,
    onScanAgain: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (isScanning) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(top = Spacing.sm),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(Spacing.xs))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(devices, key = { it.address }) { device ->
                DeviceCard(
                    device = device,
                    selected = device.address == selectedDevice?.address,
                    onClick = { onSelect(device) },
                )
            }
        }

        if (!isScanning) {
            TextButton(
                onClick = onScanAgain,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.pairing_scan_again), fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(
    device: ScannedDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (selected)
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else null,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Text(
                text = device.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = device.address,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectingView(device: ScannedDevice, bleManager: GoProBleManager) {
    val connState by bleManager.connectionState.collectAsState()
    val label = when (connState) {
        GoProConnectionState.SCANNING -> stringResource(R.string.status_scanning)
        GoProConnectionState.CONNECTING -> stringResource(R.string.pairing_connecting)
        GoProConnectionState.PAIRING -> stringResource(R.string.status_pairing)
        GoProConnectionState.CONNECTED -> stringResource(R.string.pairing_connected)
        GoProConnectionState.DISCONNECTED -> stringResource(R.string.pairing_connecting)
    }
    ProgressView(label, device.name)
}

@Composable
private fun ProgressView(label: String, deviceName: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = deviceName,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectedView(device: ScannedDevice) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.pairing_connected),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = StatusConnected,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = device.name,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManualPairingView(
    device: ScannedDevice,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenListenerSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.pairing_setup_required),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.pairing_notification_instructions, device.name),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.md))
        OutlinedButton(
            onClick = onOpenListenerSettings,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.pairing_open_notification_access), fontSize = 13.sp)
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.pairing_enable_then_connect),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.pairing_connect))
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.pairing_cancel))
        }
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.pairing_try_again))
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.pairing_cancel))
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Spacing.xl),
        )
    }
}
