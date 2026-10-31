package it.bilar.sentinel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import java.io.File
import org.json.JSONObject
import kotlin.math.pow

data class ScooterDevice(val address: String, val name: String, val rssi: Int, val advertisementHex: String = "")
data class TrafficLog(val message: String, val count: Int = 1)
data class ScooterUiState(
    val family: ScooterFamily = ScooterFamily.SENTINEL,
    val scooters: List<SavedScooter> = emptyList(),
    val activeScooterId: String = "sentinel",
    val connectionRequest: Int = 0,
    val automaticSearch: Boolean = false,
    val debugEnabled: Boolean = false,
    val userError: String? = null,
    val inspection: YouFsInspection? = null,
    val youfsSchema: List<YouFsDp> = emptyList(),
    val youfsValues: Map<Int, YouFsValue> = emptyMap(),
    val youfsProduct: String = "",
    val youfsCanCommand: Boolean = false,
    val youfsStatus: String = appText(R.string.add_your_device_profile_to_connect),
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val scanning: Boolean = false,
    val demo: Boolean = false,
    val busy: Boolean = false,
    val fresh: Boolean = false,
    val telemetry: Telemetry? = null,
    val devices: List<ScooterDevice> = emptyList(),
    val message: String = appText(R.string.your_next_ride_starts_here),
    val logs: List<TrafficLog> = emptyList()
) {
    val activeScooter get() = scooters.find { it.id == activeScooterId } ?: SavedScooter(activeScooterId, family.title, family)
    val headlightOn get() = if (family == ScooterFamily.YOUFS_TUYA) youfsSchema.find { it.code == "headlight_switch" }?.let {
        youfsValues[it.id]?.let { value -> value.type == 1 && value.value == true }
    } == true else telemetry?.lights == true
    val canCommand get() = fresh && !busy && if (family == ScooterFamily.YOUFS_TUYA) connected && !demo && youfsCanCommand
        else (connected || demo) && telemetry?.gear in 1..3
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class ScooterViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("scooter", Context.MODE_PRIVATE)
    private val registry = ScooterRegistry(application)
    private fun savedFamily() = registry.selected().family
    private val mutableState = MutableStateFlow(ScooterUiState(family = savedFamily(), scooters = registry.all(),
        activeScooterId = registry.selected().id, debugEnabled = preferences.getBoolean("debug", false)))
    val state = mutableState.asStateFlow()
    private val adapter get() = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var commandJob: Job? = null
    private var freshnessJob: Job? = null
    private var expectedFlags: Int? = null
    private var inspectedDevice: ScooterDevice? = null
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val profileStore = YouFsProfileStore(application)
    private var youfsProfile: YouFsProfile? = null
    private var youfsEngine: YouFsEngine? = null
    private var packetId: Int? = null
    private var packetTimeout: Runnable? = null
    private var addingSentinel = false
    private var autoPickJob: Job? = null

    init { reloadYouFsProfile() }

    fun refreshSavedScooters() {
        if (state.value.connected || state.value.connecting || state.value.scanning || state.value.demo) {
            mutableState.update { it.copy(scooters = registry.all()) }
        } else reloadYouFsProfile()
    }

    fun reloadYouFsProfile() {
        if (state.value.connected || state.value.connecting || state.value.scanning || state.value.demo) return
        try {
            val transferred = profileStore.consumeTransfer()
            if (transferred != null) {
                val original = registry.all().find { it.id == "youfs" } ?: SavedScooter("youfs", "YouFs-A", ScooterFamily.YOUFS_TUYA)
                registry.save(original.copy(address = transferred.json.optString("address").takeIf(String::isNotBlank) ?: original.address),profileChanged = true)
                registry.select(original.id)
            }
            val selected = registry.selected()
            youfsProfile = if (selected.family == ScooterFamily.YOUFS_TUYA) YouFsProfileStore(getApplication(), selected.profileSlot).load() else null
            mutableState.update { it.copy(family = selected.family, scooters = registry.all(), activeScooterId = selected.id,
                youfsSchema = youfsProfile?.schema.orEmpty(), youfsProduct = youfsProfile?.productId.orEmpty(),
                youfsStatus = if (youfsProfile == null) appText(R.string.add_your_device_from_settings) else "") }
        } catch (_: Exception) { message(appText(R.string.unable_to_read_the_youfs_profile_import_your_device_credentials_again)) }
    }
    fun importYouFsProfile(bytes: ByteArray): Boolean {
        try {
            val parsed = YouFsProfile.parse(bytes)
            val known = registry.all().filter { it.family == ScooterFamily.YOUFS_TUYA }.find { entry ->
                val saved = try { YouFsProfileStore(getApplication(), entry.profileSlot).load() } catch (_: Exception) { null }
                saved?.json?.optString("deviceId") == parsed.json.getString("deviceId")
            }
            val vacant = registry.all().find { it.id == "youfs" && runCatching { YouFsProfileStore(getApplication()).load() }.getOrNull() == null }
            val id = UUID.randomUUID().toString()
            val entry = known ?: vacant ?: SavedScooter(id, parsed.json.optString("name").takeIf(String::isNotBlank) ?: "YouFs-A ${registry.all().count { it.family == ScooterFamily.YOUFS_TUYA } + 1}", ScooterFamily.YOUFS_TUYA, profileSlot = id)
            registry.saveProfile(entry.copy(name = parsed.json.optString("name").takeIf(String::isNotBlank) ?: entry.name,
                address = parsed.json.optString("address").takeIf(String::isNotBlank) ?: entry.address),bytes)
            selectScooter(entry.id)
            message(appText(R.string.scooter_added))
            return true
        } catch (_: Exception) { mutableState.update { it.copy(userError = appText(R.string.unable_to_add_the_scooter)) }; return false }
        finally { bytes.fill(0) }
    }
    fun removeSelectedScooter() {
        val selected = state.value.activeScooter
        disconnect()
        registry.remove(selected.id)
        if (selected.family == ScooterFamily.YOUFS_TUYA) YouFsProfileStore(getApplication(), selected.profileSlot).forget()
        reloadYouFsProfile()
        mutableState.update { it.copy(connectionRequest = it.connectionRequest + 1, message = appText(R.string.scooter_removed)) }
    }
    fun setDebug(enabled: Boolean) {
        preferences.edit().putBoolean("debug", enabled).apply()
        mutableState.update { it.copy(debugEnabled = enabled) }
    }
    fun clearUserError(shown: String) {
        mutableState.update { if(it.userError == shown) it.copy(userError = null) else it }
    }
    fun prepareAddSentinel() { disconnect(); addingSentinel = true }
    fun canAutoConnect() = !state.value.demo && (state.value.family == ScooterFamily.SENTINEL || youfsProfile != null)
    fun connectSelected() {
        if (state.value.connected || state.value.connecting || state.value.scanning || state.value.demo) return
        if (addingSentinel) {
            mutableState.update { it.copy(family = ScooterFamily.SENTINEL) }
            scan(automatic = false); return
        }
        if (state.value.family == ScooterFamily.YOUFS_TUYA && youfsProfile == null) {
            mutableState.update { it.copy(userError = appText(R.string.add_a_scooter_from_settings_and_sign_in_to_your_youfs_a_account)) }; return
        }
        val selected = state.value.activeScooter
        if (selected.address != null) connect(ScooterDevice(selected.address, selected.name, 0))
        else scan(automatic = true)
    }
    fun selectScooter(id: String) {
        if (state.value.demo || registry.all().none { it.id == id }) return
        addingSentinel = false; disconnect(); registry.select(id); reloadYouFsProfile()
        mutableState.update { it.copy(connectionRequest = it.connectionRequest + 1) }
    }
    fun exitDemo() { disconnect(); reloadYouFsProfile(); mutableState.update { it.copy(connectionRequest = it.connectionRequest + 1) } }
    private fun rememberConnectedScooter() {
        val device = inspectedDevice ?: return
        val selected = state.value.activeScooter
        if (selected.address == device.address) return
        registry.save(selected.copy(address = device.address))
        mutableState.update { it.copy(scooters = registry.all()) }
    }
    fun queryYouFs() { if (state.value.connected && !state.value.busy) youfsEngine?.query() }
    fun sendYouFs(id: Int, input: Any) {
        if (!state.value.canCommand || state.value.family != ScooterFamily.YOUFS_TUYA) { message(state.value.youfsStatus); return }
        if (state.value.youfsSchema.none { it.id == id && it.writable }) return
        mutableState.update { it.copy(busy = true, message = appText(R.string.message_26,id)) }
        youfsEngine?.command(id, input)
    }

    private fun writeYouFsPacket(id: Int, bytes: ByteArray) {
        val current = gatt; val characteristic = tx
        if (current == null || characteristic == null || packetId != null) { youfsEngine?.completeWrite(id, appText(R.string.bluetooth_connection_is_not_ready)); return }
        packetId = id
        packetTimeout = Runnable { if (packetId == id) fail(appText(R.string.bluetooth_write_timed_out_reconnect_the_scooter)) }.also { main.postDelayed(it, 5000) }
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val accepted = if (Build.VERSION.SDK_INT >= 33) current.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
            else { characteristic.value = bytes; characteristic.writeType = type; current.writeCharacteristic(characteristic) }
        if (!accepted) fail(appText(R.string.bluetooth_did_not_accept_the_youfs_packet))
    }

    private fun youfsEvent(event: JSONObject) {
        when (event.optString("type")) {
            "error" -> fail(event.optString("message", appText(R.string.youfs_session_interrupted)))
            "result" -> {
                mutableState.update { it.copy(busy = false,message = event.optString("message"),
                    userError = if(event.optBoolean("confirmed")) it.userError else appText(R.string.engine_71)) }
                log("YouFs DP ${event.optInt("dp")} · " + if(event.optBoolean("confirmed")) appText(R.string.value_confirmed) else appText(R.string.value_not_confirmed))
            }
            "notice" -> message(event.optString("message"))
            "state" -> {
                val connected = event.optBoolean("authenticated")
                val values = event.optJSONArray("values")
                val mapped = if (values == null) emptyMap() else (0 until values.length()).associate { i ->
                    val dp = values.getJSONObject(i); dp.getInt("id") to YouFsValue(dp.getInt("type"), dp.opt("value"))
                }
                val allowed = event.optBoolean("canCommand")
                mutableState.update { it.copy(connected = connected, connecting = !connected, fresh = event.optBoolean("fresh"),
                    busy = event.optBoolean("busy"), youfsValues = mapped, youfsCanCommand = allowed,
                    youfsStatus = event.optString("reason"), telemetry = youfsTelemetry(mapped)) }
                if (connected) {
                    rememberConnectedScooter()
                    connectionJob?.cancel()
                    (getApplication<Application>() as SentinelApplication).trips.setConnectedScooter(state.value.activeScooter)
                    if (!state.value.busy && state.value.message.startsWith(appText(R.string.connecting_2))) message(appText(R.string.youfs_authenticated_automatic_data_updates_enabled))
                }
            }
        }
    }
    private fun youfsTelemetry(values: Map<Int, YouFsValue>): Telemetry? {
        fun definition(code: String) = youfsProfile?.schema?.find { it.code == code }
        fun value(code: String) = definition(code)?.let { values[it.id]?.value }
        fun numeric(code: String): Float = (value(code) as? Number)?.toFloat()?.div(10f.pow(definition(code)?.scale ?: 0)) ?: 0f
        if (value("speed") !is Number || value("battery_percentage") !is Number) return null
        val mode = (value("mode") as? Number)?.toInt() ?: 0
        var flags = mode.coerceIn(0, 3)
        if (value("headlight_switch") == true) flags = flags or 0x10
        if (value("blelock_switch") == true) flags = flags or 0x80
        if (value("cruise_switch") == true) flags = flags or 4
        if ((value("start_mode") as? Number)?.toInt() == 0) flags = flags or 8
        if ((value("unit_set") as? Number)?.toInt() == 1) flags = flags or 0x40
        return Telemetry(numeric("speed"), numeric("mileage_total"), numeric("mileage_once"), numeric("battery_percentage").toInt(), flags, 0, 0,
            numeric("ridetime_once").toInt(), SystemClock.elapsedRealtime())
    }

    private fun onMain(block: () -> Unit) {
        main.post { try { block() } catch (_: SecurityException) { fail(appText(R.string.bluetooth_permission_was_revoked)) } }
    }
    private fun message(text: String) { mutableState.update { it.copy(message = AppLanguage.translate(text)) } }
    private fun log(text: String) {
        mutableState.update { old ->
            val last = old.logs.lastOrNull()
            val logs = if (last?.message == text) old.logs.dropLast(1) + last.copy(count = last.count + 1)
                else (old.logs + TrafficLog(text)).takeLast(60)
            old.copy(logs = logs)
        }
    }

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions() = requiredPermissions().all { getApplication<Application>().checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun bluetoothEnabled() = hasPermissions() && adapter?.isEnabled == true
    fun reportPermissionDenied() { addingSentinel = false; mutableState.update { it.copy(userError = appText(R.string.allow_bluetooth_access_to_connect)) } }
    fun reportBluetoothOff() { addingSentinel = false; mutableState.update { it.copy(userError = appText(R.string.turn_on_bluetooth_to_connect)) } }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onMain {
                if (!state.value.scanning || !hasPermissions()) return@onMain
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val record = result.scanRecord
                val services = (record?.serviceUuids.orEmpty().map { it.uuid.toString() } + record?.serviceData.orEmpty().keys.map { it.uuid.toString() }).toSet()
                val tuyaManufacturer = record?.getManufacturerSpecificData(YouFsTuya.MANUFACTURER) != null
                if (!state.value.family.matchesAdvertisement(name, services, tuyaManufacturer)) return@onMain
                val device = ScooterDevice(result.device.address, name.ifBlank { "${state.value.family.title} BLE" }, result.rssi, record?.bytes?.hex().orEmpty())
                if (state.value.automaticSearch) {
                    val expected = state.value.activeScooter.address
                    if (expected != null && expected.equals(device.address, true)) connect(device)
                    else if (expected == null) {
                        mutableState.update { it.copy(devices = (it.devices.filterNot { d -> d.address == device.address } + device).sortedByDescending { it.rssi }) }
                        if (autoPickJob == null) autoPickJob = viewModelScope.launch {
                            delay(2000)
                            if (!state.value.scanning || !state.value.automaticSearch) return@launch
                            val candidates = state.value.devices
                            if (candidates.size == 1) connect(candidates.first())
                            else mutableState.update { it.copy(automaticSearch = false) }
                        }
                    }
                    return@onMain
                }
                mutableState.update { it.copy(devices = (it.devices.filterNot { d -> d.address == device.address } + device).sortedByDescending { d -> d.rssi }) }
            }
        }
        override fun onScanFailed(errorCode: Int) { onMain { closeDiscovery(); message(appText(R.string.message_29,errorCode)) } }
    }

    fun scan(automatic: Boolean = false) {
        if (state.value.family == ScooterFamily.YOUFS_TUYA && youfsProfile == null) { message(appText(R.string.add_your_youfs_a_from_settings)); return }
        if (!hasPermissions()) { reportPermissionDenied(); return }
        if (!bluetoothEnabled()) { reportBluetoothOff(); return }
        disconnect()
        mutableState.update { it.copy(scanning = true, automaticSearch = automatic, demo = false, devices = emptyList(), message = appText(R.string.message_30,it.family.title)) }
        try {
            val scanner = adapter?.bluetoothLeScanner ?: error(appText(R.string.bluetooth_le_is_unavailable))
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanJob = viewModelScope.launch {
                delay(10000)
                stopScan()
                if (automatic && state.value.devices.isEmpty()) mutableState.update { it.copy(userError = appText(R.string.scooter_not_found_turn_it_on_and_try_again)) }
                if (addingSentinel && state.value.devices.isEmpty()) { addingSentinel = false; reloadYouFsProfile() }
                message(if (state.value.devices.isEmpty()) appText(R.string.no_devices_found_turn_your_scooter_on_and_close_other_connected_apps) else appText(R.string.choose_your_scooter))
            }
        } catch (e: Exception) { closeDiscovery(); message(e.message ?: appText(R.string.search_failed)) }
    }

    fun stopScan() {
        autoPickJob?.cancel(); autoPickJob = null
        scanJob?.cancel(); scanJob = null
        try { if (hasPermissions()) adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        mutableState.update { it.copy(scanning = false) }
    }

    fun closeDiscovery() {
        stopScan()
        addingSentinel = false
        reloadYouFsProfile()
        mutableState.update { it.copy(devices = emptyList()) }
    }

    fun connect(device: ScooterDevice) {
        if (!hasPermissions()) { reportPermissionDenied(); return }
        if (addingSentinel) {
            val entry = registry.all().find { it.family == ScooterFamily.SENTINEL && (it.address == device.address || it.address == null) }
                ?: SavedScooter(UUID.randomUUID().toString(), "Jeep Sentinel ${registry.all().count { it.family == ScooterFamily.SENTINEL } + 1}", ScooterFamily.SENTINEL)
            registry.save(entry.copy(address = device.address)); registry.select(entry.id)
            addingSentinel = false
        }
        disconnect()
        reloadYouFsProfile()
        inspectedDevice = device
        mutableState.update { it.copy(connecting = true, devices = emptyList(), message = appText(R.string.message_32,device.name)) }
        try {
            val remote = adapter?.getRemoteDevice(device.address) ?: error(appText(R.string.bluetooth_is_unavailable))
            gatt = remote.connectGatt(getApplication(), false, callback, BluetoothDevice.TRANSPORT_LE)
            connectionJob = viewModelScope.launch {
                delay(if (state.value.family == ScooterFamily.YOUFS_TUYA) 30000 else 15000)
                if (!state.value.connected) fail(appText(R.string.connection_timed_out_move_closer_to_the_scooter_and_try_again))
            }
        } catch (e: Exception) { fail(e.message ?: appText(R.string.connection_failed)) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) {
            onMain {
                if (current !== gatt) return@onMain
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail(if (status == 0) appText(R.string.scooter_disconnected) else appText(R.string.message_33,status))
                } else if (newState == BluetoothProfile.STATE_CONNECTED && !current.discoverServices()) fail(appText(R.string.unable_to_read_bluetooth_services))
            }
        }
        override fun onServicesDiscovered(current: BluetoothGatt, status: Int) {
            onMain {
                if (current !== gatt) return@onMain
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(appText(R.string.message_34,status)); return@onMain }
                if (state.value.family == ScooterFamily.YOUFS_TUYA) {
                    val device = inspectedDevice
                    val inspection = YouFsInspection(device?.name.orEmpty(), device?.address.orEmpty(), device?.advertisementHex.orEmpty(),
                        System.currentTimeMillis(), current.services.map { service ->
                            BleServiceInfo(service.uuid.toString(), service.characteristics.map { BleCharacteristicInfo(it.uuid.toString(), it.properties) })
                        })
                    mutableState.update { it.copy(inspection = inspection, message = if (inspection.hasTuyaTransport)
                        appText(R.string.connecting_to_youfs_encrypted_authentication)
                        else appText(R.string.services_read_complete_tuya_fd50_transport_not_found_open_diagnostics)) }
                    if (!inspection.hasTuyaTransport || youfsProfile == null) { fail(appText(R.string.missing_youfs_transport_or_profile)); return@onMain }
                }
                val youfs = state.value.family == ScooterFamily.YOUFS_TUYA
                val service = current.getService(UUID.fromString(if (youfs) YouFsTuya.SERVICE else ScooterProtocol.SERVICE))
                tx = service?.getCharacteristic(UUID.fromString(if (youfs) YouFsTuya.TX else ScooterProtocol.TX))
                val rx = service?.getCharacteristic(UUID.fromString(if (youfs) YouFsTuya.RX else ScooterProtocol.RX))
                val writable = tx?.properties?.and(BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                if (tx == null || rx == null || !writable) { fail(appText(R.string.this_device_does_not_expose_the_sentinel_fff0_fff6_fff7_protocol)); return@onMain }
                val descriptor = rx.getDescriptor(cccd)
                val notify = rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val indicate = rx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (descriptor == null || (!notify && !indicate) || !current.setCharacteristicNotification(rx, true)) {
                    fail(appText(R.string.scooter_notifications_are_unavailable)); return@onMain
                }
                val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                val accepted = if (Build.VERSION.SDK_INT >= 33) current.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                    else { descriptor.value = value; current.writeDescriptor(descriptor) }
                if (!accepted) fail(appText(R.string.unable_to_enable_notifications))
            }
        }
        override fun onDescriptorWrite(current: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            onMain {
                if (current !== gatt || descriptor.uuid != cccd) return@onMain
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(appText(R.string.message_35,status)); return@onMain }
                if (state.value.family == ScooterFamily.YOUFS_TUYA) {
                    youfsEngine = YouFsEngine(getApplication(), ::writeYouFsPacket, ::youfsEvent).also { it.start(youfsProfile ?: return@onMain) }
                    return@onMain
                }
                connectionJob?.cancel()
                mutableState.update { it.copy(connected = true, connecting = false, message = appText(R.string.connected_waiting_for_sentinel_data)) }
                rememberConnectedScooter()
                (getApplication<Application>() as SentinelApplication).trips.setConnectedScooter(state.value.activeScooter)
                log(appText(R.string.fff6_tx_fff7_rx_connection_ready))
            }
        }
        override fun onCharacteristicChanged(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receiveCharacteristic(current, characteristic, value.copyOf())
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) receiveCharacteristic(current, characteristic, characteristic.value?.copyOf() ?: return)
        }
        override fun onCharacteristicWrite(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onMain {
                if (current === gatt && state.value.family == ScooterFamily.YOUFS_TUYA) {
                    val id = packetId ?: return@onMain
                    packetTimeout?.let(main::removeCallbacks); packetTimeout = null; packetId = null
                    if (status == BluetoothGatt.GATT_SUCCESS) youfsEngine?.completeWrite(id)
                    else fail(appText(R.string.message_36,status))
                    return@onMain
                }
                if (current === gatt && status != BluetoothGatt.GATT_SUCCESS) finishCommand(appText(R.string.message_36,status),failed = true)
            }
        }
    }

    private fun receiveCharacteristic(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        when (characteristic.uuid.toString()) {
            YouFsTuya.RX -> onMain { if (current === gatt) youfsEngine?.receive(bytes) }
            ScooterProtocol.RX -> receive(current, bytes)
        }
    }

    private fun receive(current: BluetoothGatt, bytes: ByteArray) = onMain {
        if (current !== gatt || state.value.family != ScooterFamily.SENTINEL) return@onMain
        log("RX ${bytes.hex()}")
        val parsed = ScooterProtocol.parse(bytes, SystemClock.elapsedRealtime()) ?: return@onMain
        mutableState.update { it.copy(telemetry = parsed, fresh = true) }
        freshnessJob?.cancel()
        freshnessJob = viewModelScope.launch {
            delay(ScooterProtocol.FRESH_MS)
            mutableState.update { it.copy(fresh = false, message = appText(R.string.no_recent_data_commands_are_paused)) }
        }
        if (expectedFlags == parsed.flags) finishCommand(appText(R.string.setting_confirmed_by_the_scooter))
        else if (!state.value.busy) message(appText(R.string.sentinel_connected_ready_to_ride))
    }

    fun send(setting: Setting, value: Int) {
        if (state.value.family == ScooterFamily.YOUFS_TUYA) {
            val code = when (setting) { Setting.LIGHTS -> "headlight_switch"; Setting.LOCK -> "blelock_switch"; Setting.GEAR -> "mode";
                Setting.CRUISE -> "cruise_switch"; Setting.ZERO_START -> "start_mode"; Setting.UNIT -> "unit_set" }
            val definition = state.value.youfsSchema.find { it.code == code } ?: return
            val input: Any = when (setting) {
                Setting.GEAR -> listOf("walk", "eco", "normal", "sport").getOrNull(value) ?: return
                Setting.ZERO_START -> if (value == 1) "zero_start" else "not_zero_start"
                Setting.UNIT -> if (value == 1) "mile" else "km"
                else -> value == 1
            }
            sendYouFs(definition.id, input); return
        }
        val ui = state.value
        if (!ui.canCommand) { message(appText(R.string.wait_for_a_connection_and_recent_data)); return }
        val telemetry = ui.telemetry ?: return
        try {
            if (!ui.demo) {
                check(hasPermissions()) { appText(R.string.bluetooth_permission_is_unavailable) }
                check(SystemClock.elapsedRealtime() - telemetry.receivedAt <= ScooterProtocol.FRESH_MS) { appText(R.string.scooter_data_is_out_of_date) }
            }
            val packet = ScooterProtocol.command(telemetry, setting, value)
            log("${if (ui.demo) "DEMO" else "TX"} ${setting.name} ${packet.hex()}")
            if (ui.demo) {
                mutableState.update { it.copy(telemetry = telemetry.copy(flags = ScooterProtocol.expectedFlags(packet)), message = appText(R.string.demo_simulated_setting_no_bluetooth_command_sent)) }
                return
            }
            val current = gatt ?: error(appText(R.string.scooter_disconnected))
            val characteristic = tx ?: error(appText(R.string.tx_characteristic_is_unavailable))
            expectedFlags = ScooterProtocol.expectedFlags(packet)
            mutableState.update { it.copy(busy = true, message = appText(R.string.command_sent_waiting_for_confirmation)) }
            commandJob = viewModelScope.launch { delay(3000); finishCommand(appText(R.string.no_confirmation_received_check_the_state_before_trying_again),failed = true) }
            val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val accepted = if (Build.VERSION.SDK_INT >= 33) current.writeCharacteristic(characteristic, packet, type) == BluetoothStatusCodes.SUCCESS
                else { characteristic.value = packet; characteristic.writeType = type; current.writeCharacteristic(characteristic) }
            check(accepted) { appText(R.string.bluetooth_did_not_accept_the_command) }
        } catch (e: Exception) { finishCommand(e.message ?: appText(R.string.write_failed),failed = true) }
    }

    private fun finishCommand(text: String, failed: Boolean = false) {
        commandJob?.cancel(); commandJob = null; expectedFlags = null
        mutableState.update { it.copy(busy = false,message = AppLanguage.translate(text),userError = if(failed) AppLanguage.translate(text) else it.userError) }
        log(text)
    }

    fun startDemo() {
        disconnect()
        mutableState.update { it.copy(family = ScooterFamily.SENTINEL, demo = true, fresh = true,
            telemetry = Telemetry(18.6f, 126.4f, 4.8f, 85, 2, 0, 8, 0, SystemClock.elapsedRealtime()),
            message = appText(R.string.demo_mode_simulated_data_bluetooth_disconnected)) }
    }

    fun clearLogs() { mutableState.update { it.copy(logs = emptyList()) } }


    private fun fail(text: String) {
        disconnect(); message(text); log(text)
        mutableState.update { it.copy(userError = appText(R.string.connection_failed_turn_on_the_scooter_and_close_other_connected_apps)) }
    }

    fun disconnect() {
        (getApplication<Application>() as SentinelApplication).trips.setConnectedScooter(null)
        stopScan(); connectionJob?.cancel(); commandJob?.cancel(); freshnessJob?.cancel()
        youfsEngine?.close(); youfsEngine = null
        packetTimeout?.let(main::removeCallbacks); packetTimeout = null; packetId = null
        expectedFlags = null
        val previous = gatt
        gatt = null; tx = null
        inspectedDevice = null
        try { if (hasPermissions()) previous?.disconnect() } catch (_: SecurityException) { }
        try { previous?.close() } catch (_: SecurityException) { }
        mutableState.update { it.copy(family = if (it.demo) savedFamily() else it.family,
            connected = false, connecting = false, automaticSearch = false, demo = false, busy = false, fresh = false, telemetry = null, youfsCanCommand = false, userError = null,
            youfsValues = emptyMap(), youfsStatus = appText(R.string.scooter_disconnected_connect_your_youfs_a_to_receive_data), devices = emptyList(), message = appText(R.string.scooter_disconnected_2)) }
    }

    override fun onCleared() { disconnect(); super.onCleared() }
}
