package com.lenne0815.magicshinesniffer

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class MagicshineBleSniffer(
    context: Context,
    private val listener: (String) -> Unit,
    private val deviceListener: (List<String>) -> Unit = {},
) {
    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val POLL_INTERVAL_MS = 5_000L

        const val QUERY_TEMPERATURE = "DE06A100A7ED"
        const val QUERY_BATTERY = "DE06A400A2ED"
        const val QUERY_PROFILES = "DE06A500A3ED"
        const val QUERY_ENDURANCE = "DE06A900AFED"
        const val QUERY_AB = "DE06AB00ADED"
        const val QUERY_AC = "DE06AC00AAED"
        const val QUERY_AD = "DE06AD00ABED"
        private const val MODULE_2_FULL_POWER = "DE14A2010200010A010164000000000000BB61ED"
        private const val OFFICIAL_A6 = "DE07A601EF4FED"
        private const val OFFICIAL_STEADY = "DE14A2010101010A000150000000000000BB56ED"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val centralManager by lazy { CentralManager.Factory.native(appContext, scope) }
    private val bluetoothManager by lazy { appContext.getSystemService(BluetoothManager::class.java) }
    private val operationMutex = Mutex()
    private val analyzer = FrameAnalyzer()
    @Volatile private var stateMonitorJob: Job? = null
    @Volatile private var rapidHoriCaptureJob: Job? = null
    @Volatile private var allNotifyCaptureJob: Job? = null
    private val targetService = Uuid.parse("0000FFE1-0000-1000-8000-00805f9b34fb")
    private val targetCharacteristic = Uuid.parse("0000FFE0-0000-1000-8000-00805f9b34fb")
    private val horiService = Uuid.parse("ADB425D4-B1C6-11ED-AFA1-0242AC120002")
    private val horiControlCharacteristic = Uuid.parse("8CE5DD03-0A4D-11E9-AB14-D663BD873D93")
    private val connectionOptions = CentralManager.ConnectionOptions.Direct(
        // The Hori 1300 can take longer than 8 seconds to complete the
        // Android BLE connection/service setup. Give it more time and one
        // automatic retry before declaring the connection failed.
        timeout = 20.seconds,
        retry = 1,
        retryDelay = 1.seconds,
    )

    @Volatile private var peripheral: Peripheral? = null
    @Volatile private var characteristic: RemoteCharacteristic? = null
    @Volatile private var scanJob: Job? = null
    @Volatile private var connectJob: Job? = null
    @Volatile private var notificationJob: Job? = null
    @Volatile private var pollJob: Job? = null
    private val discovered = linkedMapOf<String, Peripheral>()

    fun connect() {
        scan()
    }

    fun scan() {
        if (scanJob?.isActive == true || connectJob?.isActive == true) return
        if (!hasPermissions()) { line("ERROR missing Bluetooth permissions"); return }
        if (bluetoothManager?.adapter?.isEnabled != true) { line("ERROR Bluetooth is off"); return }
        scanJob = scope.launch {
            operationMutex.withLock {
                discovered.clear()
                deviceListener(emptyList())
                line("SCAN for M2-B0/M1-B0 lamp")
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    centralManager.scan().collect { scanResult ->
                        val name = (scanResult.advertisingData.name ?: scanResult.peripheral.name)
                            ?.replace("\u0000", "")?.replace("\uFFFD", "")?.trim().orEmpty()
                        if (name.startsWith("M2-B0", true) || name.startsWith("M2-BO", true) ||
                            name.startsWith("M1-B0", true) || name.startsWith("M1-BO", true)) {
                            val address = scanResult.peripheral.address
                            discovered[address] = scanResult.peripheral
                            deviceListener(discovered.map { entry ->
                                entry.key + " | " + (entry.value.name ?: name).replace("\u0000", "").trim()
                            })
                        }
                    }
                }
                if (discovered.isEmpty()) line("ERROR no supported lamp found")
                else line("FOUND " + discovered.size + " supported lamp(s) — select one above")
            }
        }.also { job -> job.invokeOnCompletion { if (scanJob === job) scanJob = null } }
    }

    fun connect(address: String) {
        val target = discovered[address]
        if (target == null) { line("ERROR device not found; scan again"); return }
        line("SELECTED " + (target.name ?: address))
        // Stop only the scan job. Keep the connection job independent so a tap
        // on a discovered lamp cannot be blocked by scan state.
        scanJob?.cancel()
        connectJob?.cancel()
        connectJob = scope.launch {
            operationMutex.withLock {
                runCatching { connectInternal(target) }
                    .onFailure { line("ERROR connect " + it::class.java.simpleName + ": " + it.message) }
            }
        }.also { job -> job.invokeOnCompletion { if (connectJob === job) connectJob = null } }
    }

    fun disconnect() {
        stopPolling()
        scope.launch {
            operationMutex.withLock {
                notificationJob?.cancel()
                notificationJob = null
                characteristic = null
                peripheral?.let { target ->
                    runCatching { if (target.state.value is ConnectionState.Connected) target.disconnect() }
                }
                peripheral = null
                line("DISCONNECTED")
            }
        }
    }

    fun send(label: String, frameHex: String) {
        scope.launch {
            operationMutex.withLock {
                writeInternal(label, frameHex)
            }
        }
    }

    /**
     * HORI diagnostic: send the established M1/module-1 layout used elsewhere
     * in this project, rather than the newer experimental shifted layout.
     * The value is decimal 0..100 and every exact frame is logged.
     */
    fun sendHoriRawBrightness(raw: Int) {
        val value = raw.coerceIn(0, 255)
        val content = IntArray(14)
        content[0] = 0x01
        content[4] = 0x01
        content[5] = 0x01
        content[6] = value
        content[13] = 0xBB
        val frame = IntArray(20)
        frame[0] = 0xDE
        frame[1] = 0x14
        frame[2] = 0xA2
        frame[3] = 0x01
        for (i in content.indices) frame[4 + i] = content[i]
        var checksum = frame[1]
        for (i in 2 until frame.size - 2) checksum = checksum xor frame[i]
        frame[frame.size - 2] = checksum and 0xFF
        frame[frame.size - 1] = 0xED
        val hex = frame.joinToString("") { "%02X".format(it) }
        send("HORI RAW 0x%02X (%d)".format(value, value), hex)
    }

    fun sendHoriM1Brightness(percent: Int) {
        val value = percent.coerceIn(0, 100)
        val checksum = value xor 0x5C
        val frame = "DE14A201010101%02X000150000000000000BB%02XED".format(value, checksum)
        send("HORI M1 BRIGHTNESS $value", frame)
    }

    fun sendHoriBeam(highBeam: Boolean) {
        sendHoriControl("HORI BEAM ${if (highBeam) "HIGH" else "LOW"}", "04%02X".format(if (highBeam) 1 else 0))
    }

    fun sendHoriMode(mode: Int) {
        val value = mode.coerceIn(0, 255)
        sendHoriControl("HORI MODE $value", "03%02X".format(value))
    }

    private fun sendHoriControl(label: String, commandHex: String) {
        scope.launch {
            operationMutex.withLock {
                val target = peripheral
                if (target?.state?.value !is ConnectionState.Connected) {
                    line("ERROR $label ignored: not connected")
                    return@withLock
                }
                val control = findHoriControlCharacteristic(target)
                if (control == null) {
                    line("ERROR $label: 8CE5DD03 unavailable")
                    return@withLock
                }
                line("TX HORI CONTROL REQUEST 00 -> 8CE5DD03")
                runCatching { control.write(byteArrayOf(0x00), WriteType.WITH_RESPONSE) }
                    .onFailure { line("ERROR TX HORI CONTROL REQUEST ${it::class.java.simpleName}: ${it.message}") }
                    .getOrElse { return@withLock }
                delay(60)
                line("TX $label $commandHex -> 8CE5DD03")
                runCatching { control.write(commandHex.hexToBytes(), WriteType.WITH_RESPONSE) }
                    .onFailure { line("ERROR TX $label ${it::class.java.simpleName}: ${it.message}") }
            }
        }
    }

    private suspend fun findHoriControlCharacteristic(target: Peripheral): RemoteCharacteristic? {
        val servicesFlow = target.services(listOf(horiService))
        var service = servicesFlow.value.firstOrNull()
        if (service == null) {
            repeat(8) {
                delay(60)
                service = servicesFlow.value.firstOrNull()
                if (service != null) return@repeat
            }
        }
        return service?.characteristics?.firstOrNull { it.uuid == horiControlCharacteristic }
    }

    fun runSupportSweep() {
        scope.launch {
            operationMutex.withLock {
                line("========== SUPPORT SWEEP ==========")
                listOf(
                    "A1 TEMP" to QUERY_TEMPERATURE,
                    "A4 CANDIDATE" to QUERY_BATTERY,
                    "A5 PROFILES" to QUERY_PROFILES,
                    "A9 RUNTIME" to QUERY_ENDURANCE,
                    "AB" to QUERY_AB,
                    "AC" to QUERY_AC,
                    "AD" to QUERY_AD,
                ).forEach { (label, frame) ->
                    writeInternal(label, frame)
                    delay(350)
                }
            }
        }
    }

    fun runGattSurvey() {
        scope.launch {
            operationMutex.withLock {
                val target = peripheral
                if (target?.state?.value !is ConnectionState.Connected) {
                    line("ERROR GATT READS ignored: not connected")
                    return@withLock
                }
                val services = withTimeoutOrNull(5_000L) {
                    target.services().first { it.isNotEmpty() }
                }
                if (services == null) {
                    line("ERROR GATT READS: no services")
                    return@withLock
                }

                line("========== GATT READS ==========")
                services.forEach { service ->
                    line("GATT service=${service.uuid} chars=${service.characteristics.size}")
                    service.characteristics.forEach { remoteChar ->
                        val properties = remoteChar.properties.joinToString("+")
                        line("GATT char=${remoteChar.uuid} properties=$properties")
                        if (CharacteristicProperty.READ in remoteChar.properties) {
                            runCatching { remoteChar.read() }
                                .onSuccess { value ->
                                    line("GATT READ ${remoteChar.uuid} ${value.toHex()}")
                                }
                                .onFailure { error ->
                                    line("ERROR GATT READ ${remoteChar.uuid} ${error::class.java.simpleName}: ${error.message}")
                                }
                        }
                    }
                }
            }
        }
    }

    fun runOfficialSequence() {
        scope.launch {
            operationMutex.withLock {
                line("========== OFFICIAL SEQUENCE (changes low beam briefly) ==========")
                listOf(
                    "A1" to QUERY_TEMPERATURE,
                    "A6" to OFFICIAL_A6,
                    "STEADY LOW" to OFFICIAL_STEADY,
                    "A4" to QUERY_BATTERY,
                ).forEach { (label, frame) ->
                    writeInternal(label, frame)
                    delay(180)
                }
            }
        }
    }

    fun startAllNotifyCapture() {
        stopAllNotifyCapture()
        allNotifyCaptureJob = scope.launch {
            val jobs = mutableListOf<Job>()
            operationMutex.withLock {
                val target = peripheral
                if (target?.state?.value !is ConnectionState.Connected) {
                    line("ERROR ALL NOTIFY ignored: not connected")
                    return@withLock
                }
                val services = withTimeoutOrNull(5_000L) {
                    target.services().first { it.isNotEmpty() }
                } ?: emptyList()
                val chars = services.flatMap { it.characteristics }.filter {
                    CharacteristicProperty.NOTIFY in it.properties ||
                        CharacteristicProperty.INDICATE in it.properties
                }
                line("========== ALL NOTIFY CAPTURE START ==========")
                line("Subscribing to ${chars.size} NOTIFY/INDICATE characteristic(s)")
                chars.forEach { ch ->
                    line("SUBSCRIBE ${ch.uuid} properties=${ch.properties.joinToString("+")}")
                    jobs += scope.launch {
                        runCatching {
                            ch.subscribe().collect { value ->
                                line("NOTIFY ${ch.uuid} ${value.toHex()}")
                            }
                        }.onFailure { e ->
                            if (allNotifyCaptureJob?.isActive == true) {
                                line("ERROR SUBSCRIBE ${ch.uuid} ${e::class.java.simpleName}: ${e.message}")
                            }
                        }
                    }
                }
            }
            try {
                while (true) delay(1000)
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
    }

    fun stopAllNotifyCapture() {
        val active = allNotifyCaptureJob?.isActive == true
        allNotifyCaptureJob?.cancel()
        allNotifyCaptureJob = null
        if (active) line("========== ALL NOTIFY CAPTURE STOPPED ==========")
    }

    fun startRapidHoriCapture() {
        stopRapidHoriCapture()
        stopStateMonitor()
        rapidHoriCaptureJob = scope.launch {
            line("========== RAPID HORI CAPTURE START ==========")
            line("Use FACTORY REMOTE only: LOW -> MED -> HIGH, pausing about 2 seconds at each")
            var captureChars: List<RemoteCharacteristic> = emptyList()
            operationMutex.withLock {
                val target = peripheral
                if (target?.state?.value !is ConnectionState.Connected) {
                    line("ERROR RAPID CAPTURE ignored: not connected")
                } else {
                    runCatching {
                        val services = withTimeoutOrNull(5_000L) {
                            target.services().first { it.isNotEmpty() }
                        } ?: emptyList()
                        captureChars = services.flatMap { service ->
                            service.characteristics.filter {
                                CharacteristicProperty.READ in it.properties
                            }
                        }
                        line("RAPID CAPTURE watching ${captureChars.size} readable characteristic(s) every 250ms")
                    }.onFailure { error ->
                        line("ERROR RAPID CAPTURE DISCOVERY ${error::class.java.simpleName}: ${error.message}")
                    }
                }
            }
            if (captureChars.isEmpty()) {
                line("RAPID HORI CAPTURE STOPPED: no readable characteristics")
                return@launch
            }

            val previous = mutableMapOf<String, String>()
            var pass = 0
            while (true) {
                operationMutex.withLock {
                    val target = peripheral
                    if (target?.state?.value !is ConnectionState.Connected) {
                        line("RAPID CAPTURE STOPPED: disconnected")
                        return@withLock
                    }
                    captureChars.forEach { char ->
                        runCatching { char.read() }
                            .onSuccess { value ->
                                val uuid = char.uuid.toString()
                                val hex = value.toHex()
                                val old = previous.put(uuid, hex)
                                if (pass == 0 || old != hex) {
                                    line("RAPID ${if (old == null) "BASE" else "CHANGE"} $uuid ${old?.let { "$it -> " } ?: ""}$hex")
                                }
                            }
                            .onFailure { error ->
                                if (pass == 0) line("ERROR RAPID READ ${char.uuid} ${error::class.java.simpleName}: ${error.message}")
                            }
                    }
                }
                pass++
                delay(250)
            }
        }
    }

    fun stopRapidHoriCapture() {
        val wasCapturing = rapidHoriCaptureJob?.isActive == true
        rapidHoriCaptureJob?.cancel()
        rapidHoriCaptureJob = null
        if (wasCapturing) line("========== RAPID HORI CAPTURE STOPPED ==========")
    }

    fun startStateMonitor() {
        stopStateMonitor()
        stateMonitorJob = scope.launch {
            line("STATE MONITOR: discovering readable characteristics")
            var monitorChars: List<RemoteCharacteristic> = emptyList()
            operationMutex.withLock {
                val target = peripheral
                if (target?.state?.value !is ConnectionState.Connected) {
                    line("ERROR STATE MONITOR ignored: not connected")
                } else {
                    runCatching {
                        val services = withTimeoutOrNull(5_000L) {
                            target.services().first { it.isNotEmpty() }
                        } ?: emptyList()
                        monitorChars = services.flatMap { service ->
                            service.characteristics.filter {
                                CharacteristicProperty.READ in it.properties
                            }
                        }
                        monitorChars.forEach { char ->
                            line("STATE WATCH ${char.uuid} properties=${char.properties.joinToString("+")}")
                        }
                        line("STATE MONITOR watching ${monitorChars.size} readable characteristic(s)")
                    }.onFailure { error ->
                        line("ERROR STATE DISCOVERY ${error::class.java.simpleName}: ${error.message}")
                    }
                }
            }

            if (monitorChars.isEmpty()) {
                line("STATE MONITOR STOPPED: no readable characteristics")
                return@launch
            }

            while (true) {
                operationMutex.withLock {
                    val target = peripheral
                    if (target?.state?.value !is ConnectionState.Connected) {
                        line("ERROR STATE MONITOR ignored: not connected")
                    } else {
                        monitorChars.forEach { char ->
                            runCatching { char.read() }
                                .onSuccess { value ->
                                    line("STATE ${char.uuid} ${value.toHex()}")
                                }
                                .onFailure { error ->
                                    line("ERROR STATE READ ${char.uuid} ${error::class.java.simpleName}: ${error.message}")
                                }
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    fun stopStateMonitor() {
        val wasMonitoring = stateMonitorJob?.isActive == true
        stateMonitorJob?.cancel()
        stateMonitorJob = null
        if (wasMonitoring) line("STATE MONITOR STOPPED")
    }

    fun startBatteryPolling() {
        stopPolling()
        pollJob = scope.launch {
            line("POLL A4 every ${POLL_INTERVAL_MS / 1000}s")
            while (true) {
                operationMutex.withLock { writeInternal("A4 POLL", QUERY_BATTERY) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun startFullPowerDischarge() {
        stopPolling()
        pollJob = scope.launch {
            operationMutex.withLock { writeInternal("DRAIN FULL POWER", MODULE_2_FULL_POWER) }
            delay(500)
            line("DRAIN TEST A4 every 5s; support telemetry every 60s")
            var pollCount = 0
            while (true) {
                if (peripheral?.state?.value !is ConnectionState.Connected) {
                    line("DRAIN COMPLETE OR DISCONNECTED")
                    break
                }
                operationMutex.withLock {
                    writeInternal("A4 DRAIN", QUERY_BATTERY)
                    if (pollCount % 12 == 0) {
                        writeInternal("A1 DRAIN", QUERY_TEMPERATURE)
                        delay(200)
                        writeInternal("AB DRAIN", QUERY_AB)
                        delay(200)
                        writeInternal("AC DRAIN", QUERY_AC)
                    }
                }
                pollCount++
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        val wasPolling = pollJob?.isActive == true
        pollJob?.cancel()
        pollJob = null
        if (wasPolling) line("POLL STOPPED")
    }

    fun close() {
        pollJob?.cancel()
        stateMonitorJob?.cancel()
        rapidHoriCaptureJob?.cancel()
        allNotifyCaptureJob?.cancel()
        notificationJob?.cancel()
        scanJob?.cancel()
        connectJob?.cancel()
        characteristic = null
        peripheral = null
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun connectInternal(target: Peripheral) {
        peripheral = target
        line("CONNECTING " + (target.name ?: target.address))
        centralManager.connect(target, connectionOptions)
        line("GATT CONNECTED " + target.address)
        val targetChar = awaitCharacteristic(target)
        if (targetChar == null) {
            line("ERROR FFE1/FFE0 characteristic missing")
            return
        }
        characteristic = targetChar
        notificationJob?.cancel()
        notificationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                targetChar.subscribe().collect { frame -> line(analyzer.analyze(frame)) }
            }.onFailure { line("ERROR notification " + it::class.java.simpleName + ": " + it.message) }
        }
        delay(150)
        line("READY notifications enabled; use query buttons")
    }

    private suspend fun awaitCharacteristic(target: Peripheral): RemoteCharacteristic? {
        val services = withTimeoutOrNull(10_000L) {
            target.services(listOf(targetService)).first { discovered ->
                line("SERVICES update count=${discovered.size}")
                discovered.any { service ->
                    service.characteristics.any { it.uuid == targetCharacteristic }
                }
            }
        } ?: return null

        return services.firstNotNullOfOrNull { service ->
            service.characteristics.firstOrNull { it.uuid == targetCharacteristic }
        }
    }

    private suspend fun writeInternal(label: String, frameHex: String) {
        val target = peripheral
        val targetChar = characteristic
        if (target?.state?.value !is ConnectionState.Connected || targetChar == null) {
            line("ERROR $label ignored: not connected")
            return
        }
        line("TX $label $frameHex")
        runCatching { targetChar.write(frameHex.hexToBytes(), WriteType.WITH_RESPONSE) }
            .onFailure { line("ERROR TX $label ${it::class.java.simpleName}: ${it.message}") }
    }

    private fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun line(message: String) = listener(message)
}
