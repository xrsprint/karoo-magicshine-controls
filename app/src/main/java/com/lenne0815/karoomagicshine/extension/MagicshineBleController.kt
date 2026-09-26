package com.lenne0815.karoomagicshine.extension

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.lenne0815.karoomagicshine.MagicshineProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class LampCandidate(
    val address: String,
    val name: String,
)

@OptIn(ExperimentalUuidApi::class)
class MagicshineBleController(
    context: Context,
    private var onStatus: (String) -> Unit = {},
    private var onConnectionStatus: (String) -> Unit = {},
    private var onBatteryStatus: (String) -> Unit = {},
    private var onTemperatureStatus: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "MagicshineBle"
        private const val DISCOVERY_SESSION_TIMEOUT_MS = 12_000L
        val SUPPORTED_NAME_PREFIXES = setOf(
            "M2-B0",
            "M2-BO",
            "M1-B0",
            "M1-BO",
        )
        private const val PREFS_NAME = "magicshine_prefs"
        private const val PREF_SELECTED_LAMP_ADDRESS = "selected_lamp_address"
    }

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val centralManager by lazy { CentralManager.Factory.native(appContext, scope) }
    private val bluetoothManager by lazy { appContext.getSystemService(BluetoothManager::class.java) }

    private val targetService = Uuid.parse("0000FFE1-0000-1000-8000-00805f9b34fb")
    private val targetChar = Uuid.parse("0000FFE0-0000-1000-8000-00805f9b34fb")
    private val horiService = Uuid.parse("ADB425D4-B1C6-11ED-AFA1-0242AC120002")
    private val horiControlChar = Uuid.parse("8CE5DD03-0A4D-11E9-AB14-D663BD873D93")

    private val connectionOptions by lazy {
        CentralManager.ConnectionOptions.Direct(timeout = 8.seconds, retry = 0, retryDelay = 1.seconds)
    }

    @Volatile private var discoveryJob: Job? = null
    @Volatile private var lastPeripheral: Peripheral? = null
    @Volatile private var lastTargetSeenAtMs: Long = 0L
    @Volatile private var seenCount: Int = 0
    @Volatile private var lastSeenTag: String = "none"
    @Volatile private var lastPublishedStatus: String? = null
    @Volatile private var lastPublishedConnectionStatus: String? = null
    @Volatile private var lastPublishedBatteryStatus: String? = null
    @Volatile private var lastPublishedTemperatureStatus: String? = null
    @Volatile private var lastBatteryStatusAtMs: Long = 0L
    @Volatile private var lastTemperatureStatusAtMs: Long = 0L
    @Volatile private var preferredAddress: String? = prefs.getString(PREF_SELECTED_LAMP_ADDRESS, null)
    @Volatile private var notificationJob: Job? = null
    @Volatile private var repeatingCommandJob: Job? = null
    @Volatile private var connectJob: Job? = null
    private val connectedSession = ConnectedSession(scope)
    @Volatile private var sessionPeripheral: Peripheral? = null
    @Volatile private var observingAddress: String? = null
    private val operationMutex = Mutex()
    private val candidateLock = Any()
    private val knownCandidates = LinkedHashMap<String, LampCandidate>()
    private val knownPeripherals = LinkedHashMap<String, Peripheral>()

    fun startDiscovery(forceRestart: Boolean = false) {
        clearStalePublishedConnectionState()
        if (!forceRestart && lastPeripheral?.state?.value is ConnectionState.Connected) {
            return
        }
        if (forceRestart) {
            discoveryJob?.cancel()
            discoveryJob = null
            lastPeripheral = null
            lastTargetSeenAtMs = 0L
            seenCount = 0
            lastSeenTag = "none"
            synchronized(candidateLock) {
                knownCandidates.clear()
                knownPeripherals.clear()
            }
        } else if (discoveryJob?.isActive == true) {
            return
        }
        if (!hasBlePermissions()) {
            publishStatus("missing bluetooth permissions")
            return
        }
        if (!isBluetoothEnabled()) {
            publishConnectionStatus("disconnected")
            publishStatus("disconnected")
            return
        }

        discoveryJob = scope.launch {
            seenCount = 0
            lastSeenTag = "none"
            publishStatus("searching")
            try {
                val timedOut = withTimeoutOrNull(DISCOVERY_SESSION_TIMEOUT_MS) {
                    centralManager
                        .scan()
                        .collect { result ->
                            val p = result.peripheral
                            val name = sanitizeLampName(result.advertisingData.name ?: p.name)
                            val tag = "$name/${p.address}"
                            seenCount += 1
                            lastSeenTag = tag

                            if (matchesSupportedFamily(name)) {
                                Log.d(TAG, "supported lamp seen name=$name address=${p.address} rssi=${result.rssi}")
                                val candidate = LampCandidate(address = p.address, name = name)
                                val preferred = preferredAddress
                                synchronized(candidateLock) {
                                    knownCandidates[p.address] = candidate
                                    knownPeripherals[p.address] = p
                                }
                                if (preferred != null && preferred == p.address) {
                                    val isNewTarget = lastPeripheral?.address != p.address
                                    lastPeripheral = p
                                    lastTargetSeenAtMs = System.currentTimeMillis()
                                    val shouldPublishFound =
                                        isNewTarget ||
                                            lastPublishedStatus == null ||
                                            lastPublishedStatus in setOf("searching", "disconnected", "idle")
                                    if (shouldPublishFound) {
                                        publishStatus("found")
                                    }
                                }
                            }
                        }
                } == null
                if (timedOut) {
                    Log.d(TAG, "discovery session timed out seenCount=$seenCount lastSeenTag=$lastSeenTag")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                publishStatus("discovery error: ${t::class.java.simpleName}")
            } finally {
                if (discoveryJob == kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    discoveryJob = null
                }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun setPreferredAddress(address: String?) {
        preferredAddress = address
        prefs.edit().putString(PREF_SELECTED_LAMP_ADDRESS, address).apply()
        if (address == null) {
            clearActiveConnectionState(clearCachedPeripheral = true)
            publishConnectionStatus("disconnected")
            resetTelemetryStatus()
            publishStatus("searching")
            return
        }
        synchronized(candidateLock) {
            knownPeripherals[address]?.let { peripheral ->
                lastPeripheral = peripheral
                lastTargetSeenAtMs = System.currentTimeMillis()
            }
        }
    }

    fun currentPreferredAddress(): String? = preferredAddress

    fun currentLampCandidates(): List<LampCandidate> = synchronized(candidateLock) {
        knownCandidates.values.toList()
    }

    fun currentSelectedLamp(): LampCandidate? {
        val selected = preferredAddress ?: return null
        return synchronized(candidateLock) { knownCandidates[selected] }
            ?: lastPeripheral?.let { LampCandidate(it.address, it.name ?: "Magicshine") }
    }

    private fun preferredPeripheral(): Peripheral? {
        val selected = preferredAddress ?: return null
        return synchronized(candidateLock) { knownPeripherals[selected] } ?: lastPeripheral?.takeIf { it.address == selected }
    }

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            operationMutex.withLock {
                if (preferredAddress == null) {
                    publishConnectionStatus("no device")
                    publishStatus("searching")
                    return@withLock
                }
                if (!isBluetoothEnabled()) {
                    publishConnectionStatus("disconnected")
                    publishStatus("disconnected")
                    return@withLock
                }
                publishConnectionStatus("connecting")
                val cached = preferredPeripheral().also { if (it != null) lastPeripheral = it }
                val isConnected = cached?.state?.value is ConnectionState.Connected
                if (!isConnected && cached == null) {
                    startDiscovery()
                }

                val target = awaitTarget() ?: run {
                    stopDiscovery()
                    if (!isBluetoothEnabled()) {
                        Log.d(TAG, "awaitTarget aborted: bluetooth unavailable preferred=$preferredAddress")
                        publishConnectionStatus("disconnected")
                        publishStatus("disconnected")
                    } else {
                        Log.d(
                            TAG,
                            "awaitTarget timeout preferred=$preferredAddress seenCount=$seenCount lastSeenTag=$lastSeenTag",
                        )
                        publishConnectionStatus("no device")
                    }
                    return@withLock
                }

                try {
                    publishStatus("found")
                    ensureConnected(target)
                    publishStatus("connected")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Exception) {
                    currentCoroutineContext().ensureActive()
                    Log.w(TAG, "Light connection failed", t)
                    cleanupAfterConnectionFailure(target)
                    publishStatus("ble error: ${t::class.java.simpleName}")
                    publishConnectionStatus("fehler")
                }
            }
        }.also { job ->
            job.invokeOnCompletion { if (connectJob === job) connectJob = null }
        }
    }

    fun send(frameHex: String) {
        scope.launch {
            operationMutex.withLock {
                sendInternal(frameHex)
            }
        }
    }

    fun sendHoriControl(commands: List<String>) {
        scope.launch {
            operationMutex.withLock {
                sendHoriControlInternal(commands)
            }
        }
    }

    /**
     * HORI high-beam one-shot: connect only to the dedicated HORI control
     * characteristic, send 00 + 04xx, then disconnect the BLE transport.
     * This deliberately never touches FFE0, telemetry, notifications, or the
     * legacy brightness/programming path.
     */
    fun sendHoriControlOneShot(commands: List<String>, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = operationMutex.withLock {
                val result = sendHoriControlInternal(commands)
                val target = lastPeripheral
                delay(120)
                runCatching { target?.disconnect() }
                cancelActiveJobs()
                clearActiveConnectionState(clearCachedPeripheral = true)
                stopDiscovery()
                publishConnectionStatus("disconnected")
                result
            }
            onComplete(ok)
        }
    }

    /**
     * Send a legacy Hori brightness frame and then switch beam mode as one
     * serialized BLE transaction so the writes cannot reorder.
     */
    fun sendHoriLowBeamThenBeamMode(lowBeamFrame: String, highBeam: Boolean) {
        scope.launch {
            operationMutex.withLock {
                sendHoriLowBeamThenBeamModeInternal(lowBeamFrame, highBeam)
            }
        }
    }

    fun startRepeatingCommand(frameHex: String, intervalMs: Long = 1500L): Deferred<Boolean> {
        stopRepeatingCommand()
        val firstWrite = CompletableDeferred<Boolean>()
        repeatingCommandJob = scope.launch {
            while (true) {
                val sent = operationMutex.withLock {
                    sendInternal(frameHex)
                }
                firstWrite.complete(sent)
                if (!sent) break
                delay(intervalMs)
            }
        }.also { job ->
            job.invokeOnCompletion { firstWrite.complete(false) }
        }
        return firstWrite
    }

    fun stopRepeatingCommand() {
        repeatingCommandJob?.cancel()
        repeatingCommandJob = null
    }

    fun disconnect(): Job {
        val target = lastPeripheral
        connectJob?.cancel()
        stopDiscovery()
        cancelActiveJobs()
        return scope.launch {
            operationMutex.withLock {
                disconnectSafely(
                    turnOff = {
                        if (target?.state?.value is ConnectionState.Connected) {
                            writeFrameWithRetry(
                                target,
                                MagicshineProtocol.buildHoriControlMode(0),
                            )
                        }
                    },
                    disconnect = { target?.disconnect() },
                    cleanup = {
                        if (lastPeripheral == null || lastPeripheral === target) {
                            cancelActiveJobs()
                            clearActiveConnectionState(clearCachedPeripheral = true)
                            publishStatus("disconnected")
                            publishConnectionStatus("disconnected")
                            resetTelemetryStatus()
                        }
                    },
                    onError = { Log.w(TAG, "Disconnect cleanup", it) },
                )
            }
        }
    }

    fun close() {
        disconnect().invokeOnCompletion { scope.cancel() }
    }

    private suspend fun ensureConnected(peripheral: Peripheral) {
        if (peripheral.state.value !is ConnectionState.Connected) {
            publishStatus("connecting: ${peripheral.name ?: peripheral.address}")
            publishConnectionStatus("connecting")
            centralManager.connect(peripheral, connectionOptions)
            discoveryJob?.cancel()
            discoveryJob = null
        } else {
            discoveryJob?.cancel()
            discoveryJob = null
        }
        checkNotNull(waitForTargetCharacteristic(peripheral)) { "Light characteristic unavailable" }
        ensureNotificationObservation(peripheral)
        waitUntil(timeoutMs = 40, stepMs = 8) {
            notificationJob?.isActive == true && findTargetCharacteristic(peripheral) != null
        }
        publishStatus("connected")
        publishConnectionStatus("connected")
        startConnectedSession(peripheral)
    }

    private suspend fun ensureHoriControlConnected(peripheral: Peripheral) {
        if (peripheral.state.value !is ConnectionState.Connected) {
            publishConnectionStatus("connecting")
            centralManager.connect(peripheral, connectionOptions)
            discoveryJob?.cancel()
            discoveryJob = null
        }
        checkNotNull(findHoriControlCharacteristic(peripheral)) {
            "Hori control characteristic unavailable"
        }
        publishConnectionStatus("connected")
    }

    private suspend fun cleanupAfterConnectionFailure(peripheral: Peripheral) {
        cancelActiveJobs()
        disconnectSafely(
            turnOff = {},
            disconnect = { peripheral.disconnect() },
            cleanup = {
                if (lastPeripheral === peripheral) lastPeripheral = null
                clearActiveConnectionState(clearCachedPeripheral = false)
                stopDiscovery()
                publishConnectionStatus("disconnected")
                resetTelemetryStatus()
            },
            onError = { Log.w(TAG, "Connection failure cleanup", it) },
        )
    }

    private suspend fun requestTelemetry(peripheral: Peripheral) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBatteryStatusAtMs >= 90_000) publishBatteryStatus("?")
        if (now - lastTemperatureStatusAtMs >= 90_000) publishTemperatureStatus("?")
        ensureNotificationObservation(peripheral)
        for (frame in MagicshineProtocol.telemetryRequests) {
            check(writeFrameWithRetry(peripheral, frame)) { "Telemetry characteristic unavailable" }
            delay(70)
        }
    }

    private fun startConnectedSession(peripheral: Peripheral) {
        if (sessionPeripheral === peripheral) return
        sessionPeripheral = peripheral
        connectedSession.start(
            connected = peripheral.state.map { it is ConnectionState.Connected },
            poll = {
                operationMutex.withLock {
                    if (lastPeripheral === peripheral && peripheral.state.value is ConnectionState.Connected) {
                        requestTelemetry(peripheral)
                    }
                }
            },
            onPollError = { Log.w(TAG, "Telemetry query failed", it) },
            onDisconnected = {
                operationMutex.withLock {
                    if (lastPeripheral === peripheral && peripheral.state.value !is ConnectionState.Connected) {
                        notificationJob?.cancel()
                        notificationJob = null
                        stopRepeatingCommand()
                        sessionPeripheral = null
                        clearActiveConnectionState(clearCachedPeripheral = true)
                        publishStatus("disconnected")
                        publishConnectionStatus("disconnected")
                        resetTelemetryStatus()
                    }
                }
            },
        )
    }

    private suspend fun awaitTarget(): Peripheral? {
        var p = preferredPeripheral().also { if (it != null) lastPeripheral = it } ?: lastPeripheral
        if (p?.state?.value is ConnectionState.Connected) return p
        if (!isBluetoothEnabled()) return null
        if (p == null) {
            publishStatus("searching")
            startDiscovery()
            for (i in 0 until 48) {
                if (!isBluetoothEnabled()) return null
                delay(25)
                p = preferredPeripheral().also { if (it != null) lastPeripheral = it } ?: lastPeripheral
                if (p?.state?.value is ConnectionState.Connected || p != null) break
                if (i % 12 == 11) {
                    publishStatus("searching")
                }
            }
        }
        return p
    }

    private suspend fun sendHoriControlInternal(commands: List<String>): Boolean {
        if (preferredAddress == null) {
            publishConnectionStatus("no device")
            publishStatus("searching")
            return false
        }
        val target = awaitTarget() ?: run {
            publishStatus("searching")
            publishConnectionStatus("no device")
            return false
        }

        return try {
            ensureHoriControlConnected(target)
            val control = checkNotNull(findHoriControlCharacteristic(target)) {
                "Hori control characteristic unavailable"
            }

            // Bluetooth Light Profile: request control, then issue the requested command(s).
            val request = MagicshineProtocol.buildHoriControlRequest()
            completeGattWrite { control.write(request.hexToBytes(), WriteType.WITH_RESPONSE) }
            Log.d(TAG, "HORI CTRL TX $request")

            for (command in commands) {
                delay(60)
                completeGattWrite { control.write(command.hexToBytes(), WriteType.WITH_RESPONSE) }
                Log.d(TAG, "HORI CTRL TX $command")
                delay(80)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Hori control command failed", t)
            false
        }
    }

    private suspend fun sendHoriLowBeamThenBeamModeInternal(
        lowBeamFrame: String,
        highBeam: Boolean,
    ): Boolean {
        val target = awaitTarget() ?: run {
            Log.w(TAG, "HORI sequence: no target")
            return false
        }
        ensureConnected(target)

        val legacy = checkNotNull(findTargetCharacteristic(target)) {
            "Light characteristic unavailable"
        }
        val control = checkNotNull(findHoriControlCharacteristic(target)) {
            "Hori control characteristic unavailable"
        }

        // Keep the whole sequence under operationMutex:
        // claim control -> restore LOW/MED/HIGH -> enter/leave High Beam.
        completeGattWrite {
            control.write(
                MagicshineProtocol.buildHoriControlRequest().hexToBytes(),
                WriteType.WITH_RESPONSE,
            )
        }
        delay(60)
        completeGattWrite {
            legacy.write(lowBeamFrame.hexToBytes(), WriteType.WITH_RESPONSE)
        }
        delay(60)
        completeGattWrite {
            control.write(
                MagicshineProtocol.buildHoriControlBeam(highBeam).hexToBytes(),
                WriteType.WITH_RESPONSE,
            )
        }
        Log.d(TAG, "HORI SEQUENCE low=$lowBeamFrame beamHigh=$highBeam")
        return true
    }

    private suspend fun findHoriControlCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        val servicesFlow = peripheral.services(listOf(horiService))
        var service = servicesFlow.value.firstOrNull()
        if (service == null) {
            repeat(8) {
                delay(60)
                service = servicesFlow.value.firstOrNull()
                if (service != null) return@repeat
            }
        }
        return service?.characteristics?.firstOrNull { it.uuid == horiControlChar }
    }

    private suspend fun writeFrame(peripheral: Peripheral, frameHex: String) {
        val characteristic = checkNotNull(findTargetCharacteristic(peripheral)) {
            "Light characteristic unavailable"
        }
        completeGattWrite { characteristic.write(frameHex.hexToBytes(), WriteType.WITH_RESPONSE) }
        Log.d(TAG, "TX $frameHex")
    }

    private suspend fun writeFrameWithRetry(
        peripheral: Peripheral,
        frameHex: String,
        attempts: Int = 8,
        delayMs: Long = 60,
    ): Boolean {
        repeat(attempts) { attempt ->
            val characteristic = findTargetCharacteristic(peripheral)
            if (characteristic != null) {
                completeGattWrite { characteristic.write(frameHex.hexToBytes(), WriteType.WITH_RESPONSE) }
                Log.d(TAG, "TX $frameHex")
                return true
            }
            if (attempt < attempts - 1) {
                delay(delayMs)
            }
        }
        return false
    }

    private suspend fun ensureNotificationObservation(peripheral: Peripheral) {
        if (observingAddress == peripheral.address && notificationJob?.isActive == true) return

        notificationJob?.cancel()
        val characteristic = findTargetCharacteristic(peripheral) ?: return
        observingAddress = peripheral.address
        notificationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                characteristic.subscribe().collect { data ->
                    val hex = data.toHexString()
                    parseNotifyFrame(hex)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Light notification stream failed", error)
                resetTelemetryStatus()
            }
        }
    }

    private suspend fun findTargetCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        val servicesFlow = peripheral.services(listOf(targetService))
        var service = servicesFlow.value.firstOrNull()
        if (service == null) {
            repeat(6) {
                delay(60)
                service = servicesFlow.value.firstOrNull()
                if (service != null) return@repeat
            }
        }

        if (service == null) {
            return null
        }

        val characteristic = service!!.characteristics.firstOrNull { it.uuid == targetChar }
        if (characteristic == null) {
            return null
        }
        return characteristic
    }

    private suspend fun waitForTargetCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        repeat(8) {
            val characteristic = findTargetCharacteristic(peripheral)
            if (characteristic != null) return characteristic
            delay(40)
        }
        return null
    }

    private fun parseNotifyFrame(frameHex: String) {
        Log.d(TAG, "RX $frameHex")
        val lampName = currentSelectedLamp()?.name ?: lastPeripheral?.name
        if (MagicshineProtocol.parseBatteryPercent(frameHex) != null) {
            publishBatteryStatus(MagicshineProtocol.parseBatteryStatus(frameHex, lampName) ?: "?")
        }
        MagicshineProtocol.parseTemperatureCelsius(frameHex)?.let { publishTemperatureStatus("${it}C") }
    }

    private fun matchesSupportedFamily(name: String): Boolean =
        SUPPORTED_NAME_PREFIXES.any { prefix -> name.startsWith(prefix, ignoreCase = true) }

    private fun sanitizeLampName(name: String?): String {
        val cleaned = name?.replace("\u0000", "")?.trim().orEmpty()
        return cleaned.ifBlank { "<unnamed>" }
    }

    private suspend fun waitUntil(
        timeoutMs: Long,
        stepMs: Long,
        predicate: suspend () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(stepMs)
        }
        return predicate()
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = uppercase().replace(" ", "")
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { eachByte -> "%02X".format(eachByte) }

    private fun hasBlePermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothManager?.adapter?.isEnabled == true

    private fun publishStatus(message: String) {
        val normalized = normalizeStatus(message) ?: return
        if (lastPublishedStatus == "connected" && normalized in setOf("searching", "found", "connecting")) {
            return
        }
        if (lastPublishedConnectionStatus == "connected" && normalized in setOf("searching", "found", "connecting")) {
            return
        }
        if (lastPublishedStatus == normalized) return
        lastPublishedStatus = normalized
        onStatus(normalized)
    }

    private fun normalizeStatus(message: String): String? = when {
        message.startsWith("seen[") -> null
        message.startsWith("service missing") -> null
        message.startsWith("characteristic missing") -> null
        message.startsWith("target cached") -> "found"
        message == "found" -> "found"
        message.startsWith("discovery")
            || message.startsWith("waiting for target")
            || message.startsWith("scanning...")
            || message == "searching" -> "searching"
        message.startsWith("no target")
            || message.startsWith("no device") -> "disconnected"
        message.startsWith("connecting") -> "connecting"
        message.startsWith("connected")
            || message.startsWith("sync telemetry") -> "connected"
        message.startsWith("disconnect")
            || message.startsWith("already disconnected") -> "disconnected"
        message.startsWith("missing bluetooth permissions") -> "permissions"
        message.startsWith("ble error")
            || message.startsWith("sync error")
            || message.startsWith("discovery error") -> "error"
        else -> message
    }

    private fun publishConnectionStatus(message: String) {
        if (lastPublishedConnectionStatus == message) return
        lastPublishedConnectionStatus = message
        onConnectionStatus(message)
    }

    private fun publishBatteryStatus(message: String) {
        if (message != "?") lastBatteryStatusAtMs = SystemClock.elapsedRealtime()
        if (lastPublishedBatteryStatus == message) return
        lastPublishedBatteryStatus = message
        onBatteryStatus(message)
    }

    private fun publishTemperatureStatus(message: String) {
        if (message != "?") lastTemperatureStatusAtMs = SystemClock.elapsedRealtime()
        if (lastPublishedTemperatureStatus == message) return
        lastPublishedTemperatureStatus = message
        onTemperatureStatus(message)
    }

    fun currentStatus(): String = lastPublishedStatus ?: "idle"

    fun currentConnectionStatus(): String =
        if (lastPublishedConnectionStatus == "connected" && !hasLiveConnection()) "disconnected"
        else lastPublishedConnectionStatus ?: "disconnected"

    fun currentBatteryStatus(): String = lastPublishedBatteryStatus ?: "?"

    fun currentTemperatureStatus(): String = lastPublishedTemperatureStatus ?: "?"

    fun hasLiveConnection(): Boolean {
        val peripheral = preferredPeripheral() ?: lastPeripheral
        return peripheral?.state?.value is ConnectionState.Connected
    }

    fun hasConnectInFlight(): Boolean = connectJob?.isActive == true

    fun clearStalePublishedConnectionState() {
        if (!hasConnectInFlight() && !hasLiveConnection() && lastPublishedConnectionStatus != "disconnected") {
            publishConnectionStatus("disconnected")
        }
    }

    private fun cancelActiveJobs() {
        notificationJob?.cancel()
        notificationJob = null
        repeatingCommandJob?.cancel()
        repeatingCommandJob = null
        connectedSession.stop()
        sessionPeripheral = null
    }

    private fun clearActiveConnectionState(clearCachedPeripheral: Boolean) {
        observingAddress = null
        lastTargetSeenAtMs = 0L
        lastSeenTag = "none"
        if (clearCachedPeripheral) {
            lastPeripheral = null
        }
    }

    private fun resetTelemetryStatus() {
        publishBatteryStatus("?")
        publishTemperatureStatus("?")
    }

    private suspend fun sendInternal(frameHex: String): Boolean {
        if (preferredAddress == null) {
            publishConnectionStatus("no device")
            publishStatus("searching")
            return false
        }
        val cached = preferredPeripheral().also { if (it != null) lastPeripheral = it } ?: lastPeripheral
        val isConnected = cached?.state?.value is ConnectionState.Connected
        if (!isConnected && cached == null) {
            startDiscovery()
            startDiscovery(forceRestart = true)
        }

        val target = awaitTarget()
        if (target == null) {
            stopDiscovery()
            publishStatus("searching")
            publishConnectionStatus("no device")
            return false
        }

        return try {
            ensureConnected(target)
            writeFrame(target, frameHex)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Light command failed", t)
            cleanupAfterConnectionFailure(target)
            publishStatus("ble error: ${t::class.java.simpleName}")
            false
        }
    }
}
