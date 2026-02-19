package com.clipride.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.distinctByPeripheral
import no.nordicsemi.kotlin.ble.core.BondState
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.util.mergeIndexed
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * GoPro camera connection state.
 */
enum class GoProConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    PAIRING,
    CONNECTED
}

/**
 * Thrown when BLE bonding cannot be completed programmatically.
 */
class BondingRequiredException(val address: String) :
    Exception("Device $address requires manual pairing via system Bluetooth settings")

@OptIn(ExperimentalUuidApi::class)
@Singleton
class GoProBleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val centralManager by lazy {
        CentralManager.Factory.native(context, scope)
    }

    private val connectionOptions = CentralManager.ConnectionOptions.Direct(
        timeout = 30.seconds,
        retry = 5,
        retryDelay = 3.seconds
    )

    // --- State ---

    private val _connectionState = MutableStateFlow(GoProConnectionState.DISCONNECTED)
    val connectionState: StateFlow<GoProConnectionState> = _connectionState.asStateFlow()

    private val _responses = MutableSharedFlow<BleResponse>(extraBufferCapacity = 64)
    val responses = _responses.asSharedFlow()

    private val _cameraStatus = MutableStateFlow<Map<Byte, ByteArray>>(emptyMap())
    val cameraStatus: StateFlow<Map<Byte, ByteArray>> = _cameraStatus.asStateFlow()

    // --- High-level status StateFlows ---

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0)
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    /** Timestamp of last optimistic recording state update (for debounce). */
    private var optimisticUpdateMs = 0L

    /**
     * Optimistic UI update after shutter command succeeds.
     * Real values will sync on next status push/poll.
     * Sets a debounce window (3s) to ignore conflicting ENCODING push notifications.
     */
    fun setRecordingOptimistic(recording: Boolean) {
        _isRecording.value = recording
        optimisticUpdateMs = System.currentTimeMillis()
        if (recording) {
            _recordingDuration.value = 0
        }
        updateDurationTicker()
    }

    private val _displayDuration = MutableStateFlow(0)
    val displayDuration: StateFlow<Int> = _displayDuration.asStateFlow()
    private var durationTickJob: Job? = null

    /**
     * Starts/stops local duration ticker based on recording state.
     * Ticks every second during recording, syncs with polled value from GoPro.
     */
    private fun updateDurationTicker() {
        durationTickJob?.cancel()
        if (!_isRecording.value) {
            _displayDuration.value = 0
            return
        }
        durationTickJob = scope.launch {
            _displayDuration.value = _recordingDuration.value
            while (isActive) {
                delay(1000)
                _displayDuration.value++
            }
        }
    }

    private val _sdCardRemaining = MutableStateFlow<Int?>(null)
    val sdCardRemaining: StateFlow<Int?> = _sdCardRemaining.asStateFlow()

    private val _currentPresetGroup = MutableStateFlow<Int?>(null)
    val currentPresetGroup: StateFlow<Int?> = _currentPresetGroup.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var connectedPeripheral: Peripheral? = null
    private var commandChar: RemoteCharacteristic? = null
    private var settingChar: RemoteCharacteristic? = null
    private var queryChar: RemoteCharacteristic? = null
    private var netMgmtChar: RemoteCharacteristic? = null
    private var keepAliveJob: Job? = null
    private var statusPollingJob: Job? = null
    private var notificationJobs = mutableListOf<Job>()

    // --- Persistent connection management ---

    private var connectionJob: Job? = null
    private var connectedAddress: String? = null

    // --- Scan ---

    fun scan(): kotlinx.coroutines.flow.Flow<Pair<String, String?>> {
        log("scan: starting, timeout=30s, filter=FEA6")
        return centralManager.scan(timeout = 30.seconds) {
            ServiceUuid(GoProUuid.GOPRO_SERVICE)
        }
            .filter { it.isConnectable }
            .distinctByPeripheral()
            .map { scanResult ->
                log("scan: found ${scanResult.peripheral.name ?: "?"} addr=${scanResult.peripheral.address}")
                Pair(scanResult.peripheral.address, scanResult.peripheral.name)
            }
            .catch { e ->
                log("scan: ERROR ${e.javaClass.simpleName}: ${e.message}")
            }
    }

    // --- Persistent connection (auto-connect + reconnect) ---

    /**
     * Start persistent connection to a GoPro device.
     * Idempotent — calling with the same address while active is a no-op.
     * Handles scan → connect → setup → monitor → reconnect loop.
     */
    fun startConnection(address: String) {
        if (connectedAddress == address && connectionJob?.isActive == true) {
            log("startConnection: already running for $address")
            return
        }
        stopConnection()
        connectedAddress = address
        connectionJob = scope.launch {
            connectionLoop(address)
        }
    }

    /**
     * Stop persistent connection and disconnect.
     */
    fun stopConnection() {
        connectionJob?.cancel()
        connectionJob = null
        connectedAddress = null
        val peripheral = connectedPeripheral
        teardownConnection(fullReset = true)
        if (peripheral != null) {
            scope.launch {
                try { peripheral.disconnect() } catch (_: Exception) {}
            }
        }
        _connectionState.value = GoProConnectionState.DISCONNECTED
    }

    /**
     * Reconnect loop: scan → connect → setup → wait for disconnect → repeat.
     *
     * Uses two-phase backoff:
     * - Phase 1 (rapid): MAX_RECONNECT_ATTEMPTS attempts with RECONNECT_DELAY
     * - Phase 2 (slow): unlimited attempts with SLOW_RECONNECT_DELAY (camera may be off for a long time)
     *
     * If setup fails for a BONDED device (stale bond after camera restart),
     * the bond is removed so the next attempt triggers re-pairing.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectionLoop(address: String) {
        var attempt = 0
        var setupFailedWhileBonded = false
        while (coroutineContext.isActive) {
            var peripheral: Peripheral? = null
            try {
                if (attempt > 0) {
                    val inSlowPhase = attempt >= MAX_RECONNECT_ATTEMPTS
                    val delayTime = if (inSlowPhase) SLOW_RECONNECT_DELAY else RECONNECT_DELAY
                    if (inSlowPhase) {
                        log("connection: slow-phase reconnect attempt ${attempt + 1}, delay=${delayTime}")
                        // In slow phase, update state so UI shows disconnected rather than scanning
                        _connectionState.value = GoProConnectionState.DISCONNECTED
                    } else {
                        log("connection: reconnect attempt ${attempt + 1}/$MAX_RECONNECT_ATTEMPTS")
                    }
                    delay(delayTime)
                }

                // If previous attempt failed while bonded, remove stale bond first
                if (setupFailedWhileBonded) {
                    log("connection: removing stale bond for $address before re-pair...")
                    removeBond(address)
                    delay(1000)
                    setupFailedWhileBonded = false
                }

                _connectionState.value = GoProConnectionState.SCANNING
                log("connection: scanning for $address...")
                peripheral = withTimeout(30.seconds) {
                    centralManager.scan { Address(address) }
                        .filter { it.isConnectable }
                        .map { it.peripheral }
                        .first()
                }
                log("connection: found ${peripheral.name}")

                _connectionState.value = GoProConnectionState.CONNECTING
                log("connection: connecting...")
                centralManager.connect(peripheral, connectionOptions)
                log("connection: BLE connected")

                setupConnection(peripheral)
                attempt = 0 // Reset on successful setup

                // Block until disconnect
                log("connection: monitoring for disconnect...")
                peripheral.state
                    .filter { it is ConnectionState.Disconnected }
                    .first()

                log("connection: disconnected, will reconnect")
                teardownConnection()
                _connectionState.value = GoProConnectionState.DISCONNECTED
            } catch (e: CancellationException) {
                teardownConnection()
                try { peripheral?.disconnect() } catch (_: Exception) {}
                throw e
            } catch (e: Exception) {
                log("connection: attempt ${attempt + 1} failed: ${e.javaClass.simpleName}: ${e.message}")

                // Detect stale bond: setup failed but device was BONDED
                // → camera likely lost its keys after power cycle
                if (!setupFailedWhileBonded) {
                    try {
                        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                        val btDevice = btManager.adapter.getRemoteDevice(address)
                        if (btDevice.bondState == BluetoothDevice.BOND_BONDED) {
                            log("connection: setup failed while BONDED — will remove stale bond on next attempt")
                            setupFailedWhileBonded = true
                        }
                    } catch (_: Exception) {}
                }

                teardownConnection()
                try { peripheral?.disconnect() } catch (_: Exception) {}
                attempt++
            }
        }
    }

    // --- Pairing Connect (single attempt, stays connected) ---

    /**
     * Single-attempt connection for initial pairing.
     * On success, the connection remains active — hands off to persistent monitoring.
     * Throws BondingRequiredException if bonding cannot be completed.
     */
    suspend fun connectForPairing(address: String) {
        log("pairing: START for $address")
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btDevice = btManager.adapter.getRemoteDevice(address)
        log("pairing: bondState=${bondStateName(btDevice.bondState)}")

        // Stop any existing connection
        stopConnection()

        // Clean up stuck BONDING from previous attempts
        if (btDevice.bondState == BluetoothDevice.BOND_BONDING) {
            log("pairing: stuck BONDING — removing...")
            removeBond(address)
            delay(1000)
        }

        // Single attempt: scan → connect → setup (with bonding)
        _connectionState.value = GoProConnectionState.SCANNING
        log("pairing: scanning for $address...")
        val peripheral = withTimeout(15.seconds) {
            centralManager.scan { Address(address) }
                .filter { it.isConnectable }
                .map { it.peripheral }
                .first()
        }
        log("pairing: found ${peripheral.name} (${peripheral.address})")
        _connectionState.value = GoProConnectionState.CONNECTING

        try {
            log("pairing: connecting...")
            centralManager.connect(peripheral, CentralManager.ConnectionOptions.Direct(
                timeout = 15.seconds, retry = 2, retryDelay = 2.seconds
            ))
            log("pairing: connected, running setup...")
            setupConnection(peripheral)
            log("pairing: SUCCESS — connection stays active")

            // Hand off to persistent monitoring (reconnect on disconnect)
            connectedAddress = address
            connectionJob = scope.launch {
                try {
                    peripheral.state
                        .filter { it is ConnectionState.Disconnected }
                        .first()
                    log("pairing: connection lost, starting reconnect loop")
                    teardownConnection()
                    connectionLoop(address)
                } catch (e: CancellationException) {
                    teardownConnection()
                    try { peripheral.disconnect() } catch (_: Exception) {}
                    throw e
                } catch (e: Exception) {
                    log("pairing: monitor error: ${e.javaClass.simpleName}: ${e.message}")
                    teardownConnection()
                    _connectionState.value = GoProConnectionState.DISCONNECTED
                }
            }
        } catch (e: Exception) {
            log("pairing: FAILED ${e.javaClass.simpleName}: ${e.message}")
            teardownConnection()
            try { peripheral.disconnect() } catch (_: Exception) {}
            _connectionState.value = GoProConnectionState.DISCONNECTED
            throw e
        }
    }

    /**
     * Remove system BLE bond for a device address.
     */
    @Suppress("MissingPermission")
    private fun removeBond(address: String) {
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val device = btManager?.adapter?.getRemoteDevice(address) ?: return
            val bondState = device.bondState
            log("removeBond: $address bondState=${bondStateName(bondState)}")
            if (bondState != BluetoothDevice.BOND_NONE) {
                val method = device.javaClass.getMethod("removeBond")
                method.invoke(device)
                log("removeBond: removed for $address")
            } else {
                log("removeBond: already NONE, skip")
            }
        } catch (e: Exception) {
            log("removeBond: FAILED ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // --- Post-connect setup ---

    @SuppressLint("MissingPermission")
    private suspend fun setupConnection(peripheral: Peripheral) {
        connectedPeripheral = peripheral
        setupConnectionInner(peripheral)
    }

    @SuppressLint("MissingPermission")
    private suspend fun setupConnectionInner(peripheral: Peripheral) {
        log("setup: requesting MTU...")
        peripheral.requestHighestValueLength()

        log("setup: waiting for service discovery...")
        val services = withTimeout(10.seconds) {
            peripheral.services().first { it.isNotEmpty() }
        }
        log("setup: found ${services.size} services")

        val resolved = resolveCharacteristics(services)
        log("setup: chars cmd=${resolved.cmd != null} set=${resolved.set != null} " +
            "qry=${resolved.qry != null} netMgmt=${resolved.netMgmt != null}")

        // --- BLE Bonding ---
        val bondState = peripheral.bondState.value
        log("setup: bondState=$bondState")

        if (bondState != BondState.BONDED) {
            if (!PairingNotificationListener.isEnabled()) {
                log("setup: NOT bonded and NotificationListener not active — cannot auto-confirm pairing")
                throw BondingRequiredException(peripheral.address)
            }

            val wapChar = resolved.wapPassword
            if (wapChar != null) {
                log("setup: NOT bonded — reading WAP_PASSWORD to trigger SMP pairing...")
                _connectionState.value = GoProConnectionState.PAIRING

                val pairingDeferred = CompletableDeferred<Int>()
                val bondReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        when (intent.action) {
                            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                                val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                                log("setup: BOND_STATE_CHANGED: ${bondStateName(prev)} → ${bondStateName(state)}")
                                if (prev == BluetoothDevice.BOND_BONDING && state != BluetoothDevice.BOND_BONDING) {
                                    pairingDeferred.complete(state)
                                }
                            }
                            @Suppress("MissingPermission")
                            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                                val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                                log("setup: ACTION_PAIRING_REQUEST variant=$variant (0=PIN,1=PASSKEY,2=CONFIRM,3=CONSENT)")
                                scope.launch {
                                    delay(500)
                                    repeat(5) { attempt ->
                                        val confirmed = PairingNotificationListener.tryAutoConfirmPairing()
                                        if (confirmed) {
                                            log("setup: pairing auto-confirmed via NotificationListener (attempt ${attempt + 1})")
                                            return@launch
                                        }
                                        log("setup: auto-confirm attempt ${attempt + 1}/5 — notification not found yet")
                                        delay(1000)
                                    }
                                    log("setup: WARNING — failed to auto-confirm pairing after 5 attempts")
                                }
                            }
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                    priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                }
                context.registerReceiver(bondReceiver, filter)

                try {
                    try {
                        val wapValue = withTimeout(30.seconds) {
                            wapChar.read()
                        }
                        log("setup: WAP_PASSWORD read OK (${wapValue.size} bytes) — bonding succeeded via implicit SMP")
                    } catch (e: Exception) {
                        log("setup: WAP_PASSWORD read failed: ${e.javaClass.simpleName}: ${e.message}")
                        delay(2000)
                    }

                    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val btDevice = btManager.adapter.getRemoteDevice(peripheral.address)
                    val finalBondState = btDevice.bondState
                    log("setup: final bondState=${bondStateName(finalBondState)}")

                    if (finalBondState != BluetoothDevice.BOND_BONDED) {
                        log("setup: implicit bonding failed, trying explicit createBond(TRANSPORT_LE)...")
                        val createBondDeferred = CompletableDeferred<Int>()
                        val createBondReceiver = object : BroadcastReceiver() {
                            @Suppress("MissingPermission")
                            override fun onReceive(ctx: Context, intent: Intent) {
                                when (intent.action) {
                                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                                        val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                                        log("setup: createBond BOND_STATE: ${bondStateName(prev)} → ${bondStateName(state)}")
                                        if (prev == BluetoothDevice.BOND_BONDING && state != BluetoothDevice.BOND_BONDING) {
                                            createBondDeferred.complete(state)
                                        }
                                    }
                                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                                        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                                        log("setup: createBond ACTION_PAIRING_REQUEST variant=$variant")
                                        scope.launch {
                                            delay(500)
                                            repeat(5) { attempt ->
                                                val confirmed = PairingNotificationListener.tryAutoConfirmPairing()
                                                if (confirmed) {
                                                    log("setup: createBond pairing auto-confirmed (attempt ${attempt + 1})")
                                                    return@launch
                                                }
                                                delay(1000)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val createBondFilter = IntentFilter().apply {
                            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                        }
                        context.registerReceiver(createBondReceiver, createBondFilter)
                        try {
                            val createBondMethod = btDevice.javaClass.getMethod("createBond", Int::class.javaPrimitiveType)
                            val result = createBondMethod.invoke(btDevice, 2 /* TRANSPORT_LE */) as Boolean
                            log("setup: createBond(TRANSPORT_LE) returned $result")

                            if (result) {
                                log("setup: waiting up to 35s for bond state change...")
                                val finalState = withTimeout(35.seconds) {
                                    createBondDeferred.await()
                                }
                                log("setup: bonding resolved: ${bondStateName(finalState)}")

                                if (finalState != BluetoothDevice.BOND_BONDED) {
                                    log("setup: BONDING FAILED")
                                    throw BondingRequiredException(peripheral.address)
                                }
                            }
                        } catch (e: BondingRequiredException) {
                            throw e
                        } catch (e: Exception) {
                            log("setup: createBond fallback failed: ${e.javaClass.simpleName}: ${e.message}")
                            throw BondingRequiredException(peripheral.address)
                        } finally {
                            try { context.unregisterReceiver(createBondReceiver) } catch (_: Exception) {}
                        }
                    }
                } finally {
                    try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
                }
            } else {
                log("setup: WARNING — WAP_PASSWORD char not found, cannot trigger pairing")
                log("setup: trying createBond() without WAP_PASSWORD...")
                try {
                    peripheral.createBond()
                    log("setup: createBond() succeeded")
                } catch (e: Exception) {
                    log("setup: createBond() failed: ${e.javaClass.simpleName}: ${e.message}")
                    throw BondingRequiredException(peripheral.address)
                }
            }
        } else {
            log("setup: already BONDED — skipping pairing step")
        }

        // Apply resolved characteristics
        commandChar = resolved.cmd
        settingChar = resolved.set
        queryChar = resolved.qry
        netMgmtChar = resolved.netMgmt

        if (commandChar == null || settingChar == null || queryChar == null) {
            throw IllegalStateException(
                "Required GoPro characteristics not found. " +
                "cmd=${commandChar != null} set=${settingChar != null} qry=${queryChar != null}"
            )
        }

        // Subscribe to notifications
        log("setup: subscribing to notifications...")
        startNotificationCollectors(
            resolved.cmdRsp to GoProUuid.CQ_COMMAND_RSP,
            resolved.setRsp to GoProUuid.CQ_SETTING_RSP,
            resolved.qryRsp to GoProUuid.CQ_QUERY_RSP
        )
        log("setup: notifications active")

        // Post-connect commands
        log("setup: sending post-connect commands...")
        sendPostConnectCommands()

        // Register for status updates
        log("setup: registering status updates...")
        registerStatusUpdates()

        _connectionState.value = GoProConnectionState.CONNECTED
        log("setup: CONNECTED — starting keep-alive and status polling")
        startKeepAlive()
        startStatusPolling()
    }

    private data class ResolvedChars(
        val cmd: RemoteCharacteristic?,
        val set: RemoteCharacteristic?,
        val qry: RemoteCharacteristic?,
        val cmdRsp: RemoteCharacteristic?,
        val setRsp: RemoteCharacteristic?,
        val qryRsp: RemoteCharacteristic?,
        val netMgmt: RemoteCharacteristic?,
        val wapPassword: RemoteCharacteristic?,
    )

    private fun resolveCharacteristics(services: List<RemoteService>): ResolvedChars {
        val goProService = services.firstOrNull { it.uuid == GoProUuid.GOPRO_BASE_SERVICE }
        if (goProService != null) {
            log("resolve: matched GoPro by UUID, ${goProService.characteristics.size} chars")
            for (ch in goProService.characteristics) {
                log("resolve: char ${ch.uuid}")
            }
        } else {
            log("resolve: primary UUID not found")
        }

        var cmd: RemoteCharacteristic? = null
        var set: RemoteCharacteristic? = null
        var qry: RemoteCharacteristic? = null
        var cmdRsp: RemoteCharacteristic? = null
        var setRsp: RemoteCharacteristic? = null
        var qryRsp: RemoteCharacteristic? = null
        var netMgmt: RemoteCharacteristic? = null
        var wapPassword: RemoteCharacteristic? = null

        val searchOrder = if (goProService != null) {
            listOf(goProService) + services.filter { it != goProService }
        } else {
            services
        }

        for (svc in searchOrder) {
            cmd = cmd ?: svc.findCharacteristic(GoProUuid.CQ_COMMAND)
            set = set ?: svc.findCharacteristic(GoProUuid.CQ_SETTING)
            qry = qry ?: svc.findCharacteristic(GoProUuid.CQ_QUERY)
            cmdRsp = cmdRsp ?: svc.findCharacteristic(GoProUuid.CQ_COMMAND_RSP)
            setRsp = setRsp ?: svc.findCharacteristic(GoProUuid.CQ_SETTING_RSP)
            qryRsp = qryRsp ?: svc.findCharacteristic(GoProUuid.CQ_QUERY_RSP)
            netMgmt = netMgmt ?: svc.findCharacteristic(GoProUuid.CN_NETWORK_MGMT)
            wapPassword = wapPassword ?: svc.findCharacteristic(GoProUuid.WAP_PASSWORD)
        }

        return ResolvedChars(cmd, set, qry, cmdRsp, setRsp, qryRsp, netMgmt, wapPassword)
    }

    private suspend fun startNotificationCollectors(
        vararg characteristics: Pair<RemoteCharacteristic?, Uuid>
    ) {
        for ((char, uuid) in characteristics) {
            if (char == null) {
                log("notify: characteristic $uuid NOT FOUND, skipping")
                continue
            }
            log("notify: subscribing to ${uuid.toString().take(8)}...")
            val notifFlow = withTimeout(15.seconds) {
                char.subscribe()
            }
            log("notify: ${uuid.toString().take(8)} subscribed OK")
            val job = notifFlow
                .mergeIndexed(GoProBleProtocol.goProMerge)
                .map { payload ->
                    log("notify: ${uuid.toString().take(8)} assembled ${payload.size} bytes: ${payload.take(8).joinToString(" ") { "%02x".format(it) }}")
                    GoProBleProtocol.parseResponse(uuid, payload)
                }
                .onEach { response ->
                    _responses.emit(response)
                    if (response is BleResponse.Query) {
                        log("notify: query id=0x${"%02x".format(response.queryId)} status=${response.status} map=${response.statusMap.keys.map { it.toInt() }}")
                        _cameraStatus.value = _cameraStatus.value + response.statusMap
                        handleStatusNotification(response.statusMap)
                    }
                }
                .onCompletion { cause ->
                    log("notify: $uuid collector completed: ${cause?.message ?: "normal"}")
                }
                .catch { e ->
                    log("notify: $uuid ERROR ${e.javaClass.simpleName}: ${e.message}")
                }
                .launchIn(scope)
            notificationJobs.add(job)
        }
    }

    private fun handleStatusNotification(statusMap: Map<Byte, ByteArray>) {
        for ((id, value) in statusMap) {
            when (id) {
                GoProStatus.BATTERY_PERCENTAGE -> {
                    val level = if (value.isNotEmpty()) value[0].toInt() and 0xFF else null
                    log("status: battery=$level%")
                    _batteryLevel.value = level
                }
                GoProStatus.ENCODING -> {
                    val rec = value.isNotEmpty() && value[0] != 0.toByte()
                    val sinceOptimistic = System.currentTimeMillis() - optimisticUpdateMs
                    if (sinceOptimistic < 3000 && rec != _isRecording.value) {
                        log("status: encoding=$rec IGNORED (optimistic override ${sinceOptimistic}ms ago)")
                    } else {
                        val changed = rec != _isRecording.value
                        log("status: encoding=$rec (changed=$changed)")
                        _isRecording.value = rec
                        if (changed) {
                            updateDurationTicker()
                        }
                    }
                }
                GoProStatus.VIDEO_DURATION -> {
                    val dur = bytesToInt(value)
                    log("status: duration=${dur}s")
                    _recordingDuration.value = dur
                    // Resync display ticker with actual camera value
                    if (_isRecording.value) {
                        _displayDuration.value = dur
                    }
                }
                GoProStatus.REMAINING_VIDEO -> {
                    val sec = if (value.isNotEmpty()) bytesToInt(value) else null
                    log("status: remainingVideo=${sec}s")
                    _sdCardRemaining.value = sec
                }
                GoProStatus.PRESET_GROUP -> {
                    _currentPresetGroup.value = if (value.isNotEmpty()) bytesToInt(value) else null
                }
                GoProStatus.BUSY -> {
                    _isBusy.value = value.isNotEmpty() && value[0] != 0.toByte()
                }
                GoProStatus.READY -> {
                    _isReady.value = value.isNotEmpty() && value[0] != 0.toByte()
                }
            }
        }
    }

    private suspend fun registerStatusUpdates() {
        val statusIds = byteArrayOf(
            GoProStatus.ENCODING,
            GoProStatus.BATTERY_PERCENTAGE,
            GoProStatus.REMAINING_VIDEO,
            GoProStatus.VIDEO_DURATION,
            GoProStatus.PRESET_GROUP,
            GoProStatus.BUSY,
            GoProStatus.READY
        )
        val payload = byteArrayOf(0x52) + statusIds
        val result = sendQuery(payload)
        if (result.isFailure) {
            log("registerStatus: FAILED ${result.exceptionOrNull()?.message}")
        } else {
            log("registerStatus: OK for ${statusIds.size} status IDs — battery=${_batteryLevel.value} rec=${_isRecording.value} dur=${_recordingDuration.value} sd=${_sdCardRemaining.value}")
        }
    }

    private suspend fun sendPostConnectCommands() {
        sendSetPairingComplete()
        delay(300)
        val thirdPartyResult = sendCommand(SET_THIRD_PARTY_COMMAND)
        if (thirdPartyResult.isFailure) {
            log("postConnect: SET_THIRD_PARTY FAILED: ${thirdPartyResult.exceptionOrNull()?.message}")
        } else {
            log("postConnect: SET_THIRD_PARTY OK")
        }
        val apOffResult = sendCommand(AP_OFF_COMMAND)
        if (apOffResult.isFailure) {
            log("postConnect: AP_OFF FAILED: ${apOffResult.exceptionOrNull()?.message}")
        } else {
            log("postConnect: AP_OFF OK")
        }
    }

    private suspend fun sendSetPairingComplete() {
        val char = netMgmtChar
        if (char == null) {
            log("postConnect: CM_NET_MGMT_COMM not found, skipping SetPairingComplete")
            return
        }
        try {
            val peripheral = connectedPeripheral ?: return
            val maxLen = peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE)
            val fragments = GoProBleProtocol.fragmentCommand(SET_PAIRING_COMPLETE_COMMAND, maxLen)
            for ((i, fragment) in fragments.withIndex()) {
                char.write(fragment, WriteType.WITHOUT_RESPONSE)
                if (fragments.size > 1 && i < fragments.size - 1) {
                    delay(20)
                }
            }
            log("postConnect: SetPairingComplete sent OK")
        } catch (e: Exception) {
            log("postConnect: SetPairingComplete FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun resetStatusFlows(full: Boolean = false) {
        _isRecording.value = false
        _recordingDuration.value = 0
        _displayDuration.value = 0
        _isBusy.value = false
        _isReady.value = false
        if (full) {
            _batteryLevel.value = null
            _sdCardRemaining.value = null
            _currentPresetGroup.value = null
            _cameraStatus.value = emptyMap()
        }
    }

    private fun teardownConnection(fullReset: Boolean = false) {
        keepAliveJob?.cancel()
        keepAliveJob = null
        statusPollingJob?.cancel()
        statusPollingJob = null
        durationTickJob?.cancel()
        durationTickJob = null
        notificationJobs.forEach { it.cancel() }
        notificationJobs.clear()
        commandChar = null
        settingChar = null
        queryChar = null
        netMgmtChar = null
        connectedPeripheral = null
        resetStatusFlows(full = fullReset)
    }

    // --- Command sending ---

    suspend fun sendCommand(data: ByteArray): Result<BleResponse> {
        val char = commandChar ?: return Result.failure(IllegalStateException("Not connected"))
        return writeAndWait(char, data, GoProUuid.CQ_COMMAND_RSP)
    }

    suspend fun sendSetting(data: ByteArray): Result<BleResponse> {
        val char = settingChar ?: return Result.failure(IllegalStateException("Not connected"))
        return writeAndWait(char, data, GoProUuid.CQ_SETTING_RSP)
    }

    suspend fun sendQuery(data: ByteArray): Result<BleResponse> {
        val char = queryChar ?: return Result.failure(IllegalStateException("Not connected"))
        return writeAndWait(char, data, GoProUuid.CQ_QUERY_RSP)
    }

    private suspend fun writeAndWait(
        char: RemoteCharacteristic,
        data: ByteArray,
        expectedResponseUuid: Uuid,
        timeoutSec: Long = 15,
        retries: Int = 3
    ): Result<BleResponse> {
        val expectedCommandId = data.firstOrNull() ?: return Result.failure(
            IllegalArgumentException("Empty command data")
        )

        repeat(retries) { attempt ->
            try {
                val deferred = CompletableDeferred<BleResponse>()

                val listenerJob = scope.launch {
                    responses.first { response ->
                        matchesResponse(response, expectedResponseUuid, expectedCommandId)
                    }.let { deferred.complete(it) }
                }

                try {
                    val peripheral = connectedPeripheral
                        ?: return Result.failure(IllegalStateException("Not connected"))
                    val maxLen = peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE)
                    val fragments = GoProBleProtocol.fragmentCommand(data, maxLen)

                    for ((i, fragment) in fragments.withIndex()) {
                        char.write(fragment, WriteType.WITHOUT_RESPONSE)
                        if (fragments.size > 1 && i < fragments.size - 1) {
                            delay(20)
                        }
                    }

                    val result = withTimeout(timeoutSec.seconds) {
                        deferred.await()
                    }
                    return Result.success(result)
                } finally {
                    listenerJob.cancel()
                }
            } catch (e: Exception) {
                log("cmd: attempt ${attempt + 1}/$retries FAILED (id=0x${"%02x".format(expectedCommandId)}): ${e.message}")
                if (attempt < retries - 1) delay(500)
            }
        }
        return Result.failure(Exception("Command failed after $retries retries"))
    }

    private fun matchesResponse(
        response: BleResponse,
        sourceUuid: Uuid,
        expectedId: Byte
    ): Boolean {
        return when {
            sourceUuid == GoProUuid.CQ_COMMAND_RSP && response is BleResponse.Command ->
                response.commandId == expectedId
            sourceUuid == GoProUuid.CQ_COMMAND_RSP && response is BleResponse.Protobuf ->
                response.featureId == expectedId
            sourceUuid == GoProUuid.CQ_SETTING_RSP && response is BleResponse.Setting ->
                response.settingId == expectedId
            sourceUuid == GoProUuid.CQ_QUERY_RSP && response is BleResponse.Query ->
                response.queryId == expectedId
            else -> false
        }
    }

    // --- Keep-alive ---

    private fun startKeepAlive(intervalMs: Long = KEEP_ALIVE_INTERVAL_MS) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            var consecutiveErrors = 0
            while (isActive) {
                delay(intervalMs)
                val result = sendSetting(KEEP_ALIVE_COMMAND)
                if (result.isSuccess) {
                    consecutiveErrors = 0
                } else {
                    consecutiveErrors++
                    log("keepAlive: FAILED ($consecutiveErrors consecutive)")
                    if (consecutiveErrors >= MAX_KEEP_ALIVE_FAILURES) {
                        log("keepAlive: $MAX_KEEP_ALIVE_FAILURES failures — connection lost")
                        break
                    }
                }
            }
        }
    }

    // --- Status Polling ---

    private fun startStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = scope.launch {
            while (isActive) {
                try {
                    pollStatus()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("statusPoll: FAILED ${e.javaClass.simpleName}: ${e.message}")
                }
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollStatus() {
        val char = queryChar ?: return
        val peripheral = connectedPeripheral ?: return

        val statusIds = byteArrayOf(
            GoProStatus.ENCODING,
            GoProStatus.BATTERY_PERCENTAGE,
            GoProStatus.REMAINING_VIDEO,
            GoProStatus.VIDEO_DURATION,
            GoProStatus.PRESET_GROUP,
            GoProStatus.BUSY,
            GoProStatus.READY
        )
        val payload = byteArrayOf(0x13) + statusIds
        val maxLen = peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE)
        val fragments = GoProBleProtocol.fragmentCommand(payload, maxLen)

        for ((i, fragment) in fragments.withIndex()) {
            char.write(fragment, WriteType.WITHOUT_RESPONSE)
            if (fragments.size > 1 && i < fragments.size - 1) {
                delay(20)
            }
        }
    }

    // --- Helpers ---

    private fun RemoteService.findCharacteristic(uuid: Uuid): RemoteCharacteristic? {
        return characteristics.firstOrNull { it.uuid == uuid }
    }

    fun disconnect() {
        log("disconnect: requested")
        stopConnection()
    }

    companion object {
        private const val TAG = "ClipRide"

        const val MAX_RECONNECT_ATTEMPTS = 10
        val RECONNECT_DELAY = 3.seconds
        val SLOW_RECONNECT_DELAY = 15.seconds
        const val KEEP_ALIVE_INTERVAL_MS = 60_000L
        const val STATUS_POLL_INTERVAL_MS = 5_000L
        const val MAX_KEEP_ALIVE_FAILURES = 3

        val KEEP_ALIVE_COMMAND = byteArrayOf(0x5B, 0x01, 0x42)
        val SET_THIRD_PARTY_COMMAND = byteArrayOf(0xF1.toByte(), 0x69, 0x08, 0x02)
        val AP_OFF_COMMAND = byteArrayOf(0x17, 0x01, 0x00)

        val SET_PAIRING_COMPLETE_COMMAND = byteArrayOf(
            0x03, 0x01,
            0x08, 0x00,
            0x12, 0x08,
            0x43, 0x6C, 0x69, 0x70, 0x52, 0x69, 0x64, 0x65 // "ClipRide"
        )

        fun bytesToInt(bytes: ByteArray): Int {
            var result = 0
            for (b in bytes) {
                result = (result shl 8) or (b.toInt() and 0xFF)
            }
            return result
        }

        private fun log(msg: String) {
            Timber.tag(TAG).d(msg)
        }

        private fun bondStateName(state: Int): String = when (state) {
            android.bluetooth.BluetoothDevice.BOND_NONE -> "NONE"
            android.bluetooth.BluetoothDevice.BOND_BONDING -> "BONDING"
            android.bluetooth.BluetoothDevice.BOND_BONDED -> "BONDED"
            else -> "UNKNOWN($state)"
        }
    }
}
