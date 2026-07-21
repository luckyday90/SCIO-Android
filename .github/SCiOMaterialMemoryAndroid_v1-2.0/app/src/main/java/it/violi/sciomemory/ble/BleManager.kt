package it.violi.sciomemory.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import it.violi.sciomemory.model.BleConnectionState
import it.violi.sciomemory.model.BleDeviceInfo
import it.violi.sciomemory.model.GattCharacteristicInfo
import it.violi.sciomemory.model.PacketEvent
import it.violi.sciomemory.model.ScanProgress
import it.violi.sciomemory.model.ScioScan
import it.violi.sciomemory.model.TemperatureReading
import it.violi.sciomemory.model.WhiteReferenceState
import it.violi.sciomemory.protocol.AssemblyUpdate
import it.violi.sciomemory.protocol.ScioProtocol
import it.violi.sciomemory.protocol.ScioScanAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class BleManager(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val assembler = ScioScanAssembler()

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationCharacteristic: BluetoothGattCharacteristic? = null
    private var scanStopJob: Job? = null
    private var assemblyTimeoutJob: Job? = null
    private var acquisitionMode: String = "sample"

    private val discovered = linkedMapOf<String, BleDeviceInfo>()

    private val _devices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val devices: StateFlow<List<BleDeviceInfo>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _connectedAddress = MutableStateFlow<String?>(null)
    val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()

    private val _statusMessage = MutableStateFlow("Bluetooth non connesso")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _characteristics = MutableStateFlow<List<GattCharacteristicInfo>>(emptyList())
    val characteristics: StateFlow<List<GattCharacteristicInfo>> = _characteristics.asStateFlow()

    private val _packetLog = MutableStateFlow<List<PacketEvent>>(emptyList())
    val packetLog: StateFlow<List<PacketEvent>> = _packetLog.asStateFlow()

    private val _lastPacket = MutableStateFlow<PacketEvent?>(null)
    val lastPacket: StateFlow<PacketEvent?> = _lastPacket.asStateFlow()

    private val _latestScan = MutableStateFlow<ScioScan?>(null)
    val latestScan: StateFlow<ScioScan?> = _latestScan.asStateFlow()

    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    private val _protocolMessages = MutableStateFlow<List<String>>(emptyList())
    val protocolMessages: StateFlow<List<String>> = _protocolMessages.asStateFlow()

    private val _temperature = MutableStateFlow<TemperatureReading?>(null)
    val temperature: StateFlow<TemperatureReading?> = _temperature.asStateFlow()

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    private val _whiteReference = MutableStateFlow(WhiteReferenceState())
    val whiteReference: StateFlow<WhiteReferenceState> = _whiteReference.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val name = runCatching { device.name }.getOrNull()
                ?: result.scanRecord?.deviceName
                ?: "Dispositivo senza nome"
            val advertisement = result.scanRecord?.bytes?.toHex().orEmpty()
            discovered[address] = BleDeviceInfo(address, name, result.rssi, advertisement)
            _devices.value = discovered.values.sortedWith(
                compareByDescending<BleDeviceInfo> { it.name.contains("scio", ignoreCase = true) }
                    .thenByDescending { it.rssi }
            )
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            _connectionState.value = BleConnectionState.ERROR
            _statusMessage.value = "Scansione BLE fallita: codice $errorCode"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectedAddress.value = gatt.device.address
                    _connectionState.value = BleConnectionState.DISCOVERING
                    _statusMessage.value = "Connesso. Ricerca dei servizi GATT..."
                    if (!hasConnectPermission()) return
                    @SuppressLint("MissingPermission")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clearConnection("Disconnesso dallo SCiO")
                }
                else -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        _connectionState.value = BleConnectionState.ERROR
                        _statusMessage.value = "Errore GATT $status"
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.ERROR
                _statusMessage.value = "Servizi GATT non disponibili: $status"
                return
            }

            val infos = gatt.services.flatMap { service ->
                service.characteristics.map { characteristic ->
                    GattCharacteristicInfo(
                        serviceUuid = service.uuid.toString(),
                        characteristicUuid = characteristic.uuid.toString(),
                        properties = characteristic.properties
                    )
                }
            }
            _characteristics.value = infos

            commandCharacteristic = gatt.services
                .asSequence()
                .flatMap { it.characteristics.asSequence() }
                .firstOrNull { it.uuid == ScioProtocol.COMMAND_CHARACTERISTIC_UUID }

            val command = commandCharacteristic
            if (command == null) {
                _connectionState.value = BleConnectionState.ERROR
                _statusMessage.value = "UUID SCiO 3492 non trovato. Consultare l'elenco GATT."
                return
            }

            notificationCharacteristic = command.service.characteristics.firstOrNull {
                it.uuid != command.uuid && it.supportsNotifyOrIndicate()
            } ?: gatt.services.asSequence()
                .flatMap { it.characteristics.asSequence() }
                .firstOrNull { it.supportsNotifyOrIndicate() }

            val notification = notificationCharacteristic
            if (notification == null) {
                _connectionState.value = BleConnectionState.ERROR
                _statusMessage.value = "Nessuna caratteristica NOTIFY/INDICATE trovata."
                return
            }

            _connectionState.value = BleConnectionState.CONFIGURING
            _statusMessage.value = "Abilitazione notifiche ${notification.uuid}..."
            enableNotifications(gatt, notification)
        }

        @Deprecated("Compatibilità con Android precedenti ad API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicValue(gatt, characteristic, characteristic.value ?: ByteArray(0), "NOTIFY")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicValue(gatt, characteristic, value, "NOTIFY")
        }

        @Deprecated("Compatibilità con Android precedenti ad API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(gatt, characteristic, characteristic.value ?: ByteArray(0), "READ")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(gatt, characteristic, value, "READ")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _connectionState.value = BleConnectionState.READY
                    _statusMessage.value = "Pronto. Comandi SCiO disponibili."
                } else {
                    _connectionState.value = BleConnectionState.ERROR
                    _statusMessage.value = "Impossibile abilitare le notifiche: $status"
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addProtocolMessage("Scrittura fallita su ${characteristic.uuid}: $status")
            }
        }
    }

    fun startScan() {
        if (!hasScanPermission()) {
            _statusMessage.value = "Autorizzazione Bluetooth mancante"
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState.ERROR
            _statusMessage.value = "Bluetooth LE non disponibile"
            return
        }

        stopScan()
        discovered.clear()
        _devices.value = emptyList()
        _isScanning.value = true
        _connectionState.value = BleConnectionState.SCANNING
        _statusMessage.value = "Ricerca BLE per 12 secondi..."

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        @SuppressLint("MissingPermission")
        scanner.startScan(null, settings, scanCallback)

        scanStopJob = scope.launch {
            delay(12_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanStopJob?.cancel()
        scanStopJob = null
        if (_isScanning.value && hasScanPermission()) {
            @SuppressLint("MissingPermission")
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        _isScanning.value = false
        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.DISCONNECTED
            _statusMessage.value = "Ricerca terminata"
        }
    }

    fun connect(address: String) {
        if (!hasConnectPermission()) {
            _statusMessage.value = "Autorizzazione di connessione mancante"
            return
        }
        stopScan()
        disconnect()
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            _connectionState.value = BleConnectionState.ERROR
            _statusMessage.value = "Dispositivo non valido"
            return
        }

        _connectionState.value = BleConnectionState.CONNECTING
        _statusMessage.value = "Connessione a $address..."
        @SuppressLint("MissingPermission")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        assemblyTimeoutJob?.cancel()
        assembler.reset()
        _scanProgress.value = ScanProgress()
        if (hasConnectPermission()) {
            @SuppressLint("MissingPermission")
            bluetoothGatt?.disconnect()
            @SuppressLint("MissingPermission")
            bluetoothGatt?.close()
        }
        clearConnection("Bluetooth non connesso")
    }

    fun read(info: GattCharacteristicInfo) {
        val gatt = bluetoothGatt ?: return
        if (!hasConnectPermission()) return
        val characteristic = findCharacteristic(gatt, info.serviceUuid, info.characteristicUuid) ?: return
        if (!characteristic.supports(BluetoothGattCharacteristic.PROPERTY_READ)) return
        @SuppressLint("MissingPermission")
        gatt.readCharacteristic(characteristic)
    }

    fun subscribe(info: GattCharacteristicInfo) {
        val gatt = bluetoothGatt ?: return
        val characteristic = findCharacteristic(gatt, info.serviceUuid, info.characteristicUuid) ?: return
        if (characteristic.supportsNotifyOrIndicate()) enableNotifications(gatt, characteristic)
    }

    fun requestBattery() = sendCommand(ScioProtocol.BATTERY_COMMAND)
    fun requestTemperature() = sendCommand(ScioProtocol.TEMPERATURE_COMMAND)

    fun startScioScan() {
        acquisitionMode = "sample"
        _latestScan.value = null
        assembler.reset()
        _scanProgress.value = ScanProgress(active = true, message = "Comando di scansione inviato")
        sendCommand(ScioProtocol.SCAN_COMMAND)
    }

    fun acquireWhiteReference() {
        if (_connectionState.value != BleConnectionState.READY) {
            _statusMessage.value = "SCiO non pronto"
            return
        }
        acquisitionMode = "white"
        assembler.reset()
        _scanProgress.value = ScanProgress(active = true, message = "Preparazione riferimento bianco")
        sendCommand(ScioProtocol.WHITE_REFERENCE_READY_COMMAND)
        scope.launch {
            delay(350)
            sendCommand(ScioProtocol.TEMPERATURE_COMMAND)
            delay(250)
            sendCommand(ScioProtocol.SCAN_COMMAND)
        }
    }

    fun clearWhiteReference() {
        _whiteReference.value = WhiteReferenceState()
    }

    fun clearLog() {
        _packetLog.value = emptyList()
        _protocolMessages.value = emptyList()
    }

    private fun sendCommand(command: ByteArray) {
        val gatt = bluetoothGatt
        val characteristic = commandCharacteristic
        if (gatt == null || characteristic == null || _connectionState.value != BleConnectionState.READY) {
            _statusMessage.value = "SCiO non pronto"
            return
        }
        if (!hasConnectPermission()) return

        val writeType = if (characteristic.supports(BluetoothGattCharacteristic.PROPERTY_WRITE)) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            @SuppressLint("MissingPermission")
            gatt.writeCharacteristic(characteristic, command, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = command
            @SuppressLint("MissingPermission", "DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        if (accepted) {
            addProtocolMessage("TX ${ScioProtocol.commandName(command)}: ${ScioProtocol.hex(command)}")
        } else {
            addProtocolMessage("Comando non accettato dalla coda GATT")
        }
    }

    private fun handleCharacteristicValue(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        kind: String
    ) {
        val event = PacketEvent(
            timestamp = System.currentTimeMillis(),
            kind = kind,
            deviceAddress = gatt.device.address,
            serviceUuid = characteristic.service.uuid.toString(),
            characteristicUuid = characteristic.uuid.toString(),
            payload = value.copyOf()
        )
        _lastPacket.value = event
        _packetLog.value = (listOf(event) + _packetLog.value).take(MAX_PACKETS)

        if (characteristic.uuid != notificationCharacteristic?.uuid || kind != "NOTIFY") return

        when (ScioProtocol.responseType(value)) {
            ScioProtocol.RESPONSE_BATTERY -> {
                _batteryPercent.value = ScioProtocol.parseBatteryPercent(value)
                addProtocolMessage("RX batteria: ${ScioProtocol.hex(value)}")
            }
            ScioProtocol.RESPONSE_TEMPERATURE -> {
                ScioProtocol.parseTemperature(value)?.let { (cmos, chip, obj) ->
                    _temperature.value = TemperatureReading(cmos, chip, obj, event.timestamp, ScioProtocol.hex(value))
                }
                addProtocolMessage("RX temperatura: ${ScioProtocol.hex(value)}")
            }
        }

        val shouldAssemble = assembler.isActive() || ScioProtocol.responseType(value) == ScioProtocol.RESPONSE_SCAN
        if (!shouldAssemble) return

        when (val update = assembler.accept(value, gatt.device.address, event.timestamp)) {
            is AssemblyUpdate.Progress -> {
                _scanProgress.value = update.value
                restartAssemblyTimeout()
            }
            is AssemblyUpdate.Completed -> {
                assemblyTimeoutJob?.cancel()
                if (acquisitionMode == "white") {
                    _whiteReference.value = WhiteReferenceState(
                        scan = update.scan,
                        capturedAt = update.scan.capturedAt,
                        message = if (update.scan.complete) "Riferimento bianco acquisito" else "Riferimento bianco incompleto"
                    )
                } else {
                    _latestScan.value = update.scan
                }
                _scanProgress.value = ScanProgress(
                    active = false,
                    sectionIndex = update.scan.sections.size - 1,
                    sequence = 0,
                    packetCount = update.scan.rawPackets.size,
                    message = "${update.scan.completionReason}: ${update.scan.sections.map { it.size }} byte"
                )
                addProtocolMessage("Scansione conclusa: ${update.scan.sections.map { it.size }} byte")
            }
            is AssemblyUpdate.Ignored -> Unit
        }
    }

    private fun restartAssemblyTimeout() {
        assemblyTimeoutJob?.cancel()
        assemblyTimeoutJob = scope.launch {
            delay(2_500)
            when (val update = assembler.finishOnTimeout()) {
                is AssemblyUpdate.Completed -> {
                    if (acquisitionMode == "white") {
                        _whiteReference.value = WhiteReferenceState(update.scan, update.scan.capturedAt, "Riferimento bianco incompleto")
                    } else {
                        _latestScan.value = update.scan
                    }
                    _scanProgress.value = ScanProgress(
                        active = false,
                        packetCount = update.scan.rawPackets.size,
                        message = "${update.scan.completionReason}: ${update.scan.sections.map { it.size }} byte"
                    )
                    addProtocolMessage("Scansione incompleta chiusa per timeout")
                }
                else -> Unit
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasConnectPermission()) return
        @SuppressLint("MissingPermission")
        val locallyEnabled = gatt.setCharacteristicNotification(characteristic, true)
        if (!locallyEnabled) {
            _connectionState.value = BleConnectionState.ERROR
            _statusMessage.value = "setCharacteristicNotification non riuscito"
            return
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            _connectionState.value = BleConnectionState.READY
            _statusMessage.value = "Notifiche locali attive; descrittore CCCD non esposto"
            return
        }
        val value = if (characteristic.supports(BluetoothGattCharacteristic.PROPERTY_INDICATE)) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            @SuppressLint("MissingPermission")
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @SuppressLint("MissingPermission", "DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!accepted) {
            _connectionState.value = BleConnectionState.ERROR
            _statusMessage.value = "Scrittura del descrittore CCCD non avviata"
        }
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: String,
        characteristicUuid: String
    ): BluetoothGattCharacteristic? = runCatching {
        gatt.getService(UUID.fromString(serviceUuid))
            ?.getCharacteristic(UUID.fromString(characteristicUuid))
    }.getOrNull()

    private fun clearConnection(message: String) {
        bluetoothGatt = null
        commandCharacteristic = null
        notificationCharacteristic = null
        _connectedAddress.value = null
        _characteristics.value = emptyList()
        _connectionState.value = BleConnectionState.DISCONNECTED
        _statusMessage.value = message
    }

    private fun addProtocolMessage(message: String) {
        _protocolMessages.value = (listOf(message) + _protocolMessages.value).take(100)
    }

    private fun BluetoothGattCharacteristic.supports(property: Int): Boolean = properties and property != 0

    private fun BluetoothGattCharacteristic.supportsNotifyOrIndicate(): Boolean =
        supports(BluetoothGattCharacteristic.PROPERTY_NOTIFY) ||
            supports(BluetoothGattCharacteristic.PROPERTY_INDICATE)

    private fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    companion object {
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_PACKETS = 500
    }
}
