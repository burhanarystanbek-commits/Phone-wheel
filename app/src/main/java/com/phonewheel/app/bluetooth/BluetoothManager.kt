package com.phonewheel.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as SystemBluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.IOException
import java.util.UUID

/** Same-named simple data holder so the UI layer doesn't need to import
 *  android.bluetooth.BluetoothDevice directly everywhere. */
data class BtDevice(
    val name: String,
    val address: String,
    val bonded: Boolean,
)

enum class BtStatus {
    NOT_CONNECTED,
    SEARCHING,
    CONNECTING,
    CONNECTED,
    CONNECTION_LOST,
}

/**
 * Wraps Android's Bluetooth Classic (RFCOMM) APIs behind a small,
 * permission-aware surface:
 *   - lists already-paired devices
 *   - discovers nearby unpaired devices
 *   - connects/disconnects an RFCOMM socket to a chosen device
 *   - exposes raw line-based I/O for whoever owns the socket (BluetoothTransport)
 *
 * This class does NOT request permissions itself — Android requires runtime
 * permission requests to happen from an Activity, so MainActivity owns the
 * permission-request flow and only calls into this class once permissions
 * are confirmed granted. Every public method here re-checks permissions
 * defensively and fails safely (returns false / empty list / invokes the
 * error callback) rather than crashing with a SecurityException if it's
 * ever called too early.
 */
class BluetoothManager(private val context: Context) {

    companion object {
        /** A fixed, well-known RFCOMM UUID for this app's own service. Both
         *  the Android client and the Windows RFCOMM server must agree on
         *  this UUID for the connection to be discoverable rendezvous-style;
         *  it does not need to be secret, only consistent. */
        val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        const val SERVICE_NAME = "PhoneWheel"
    }

    private val systemBtManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? SystemBluetoothManager
    private val adapter: BluetoothAdapter? = systemBtManager?.adapter

    private var socket: BluetoothSocket? = null
    private var connectThread: Thread? = null
    private var discoveryReceiver: BroadcastReceiver? = null

    var status: BtStatus = BtStatus.NOT_CONNECTED
        private set

    var connectedDevice: BtDevice? = null
        private set

    private var onStatusChanged: ((BtStatus) -> Unit)? = null
    private var onDeviceFound: ((BtDevice) -> Unit)? = null
    private var onLog: ((String, String) -> Unit)? = null
    private var onLineReceived: ((String) -> Unit)? = null

    fun setOnStatusChanged(cb: (BtStatus) -> Unit) { onStatusChanged = cb }
    fun setOnDeviceFound(cb: (BtDevice) -> Unit) { onDeviceFound = cb }
    fun setOnLog(cb: (String, String) -> Unit) { onLog = cb }
    fun setOnLineReceived(cb: (String) -> Unit) { onLineReceived = cb }

    private fun setStatus(s: BtStatus) {
        status = s
        onStatusChanged?.invoke(s)
    }

    fun isSupported(): Boolean = adapter != null

    fun isEnabled(): Boolean = adapter?.isEnabled == true

    /** True once every Bluetooth permission this app needs for the running
     *  Android version has been granted. MainActivity checks this before
     *  calling connect()/startDiscovery() and otherwise drives the runtime
     *  permission request dialog itself. */
    fun hasPermissions(): Boolean {
        val connectGranted = hasPermission(permissionConnect())
        val scanGranted = hasPermission(permissionScan())
        return connectGranted && scanGranted
    }

    private fun permissionConnect(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.Manifest.permission.BLUETOOTH_CONNECT
        else android.Manifest.permission.BLUETOOTH

    private fun permissionScan(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.Manifest.permission.BLUETOOTH_SCAN
        else android.Manifest.permission.ACCESS_COARSE_LOCATION

    private fun hasPermission(perm: String): Boolean =
        context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The two permission strings MainActivity should request together via
     *  ActivityCompat.requestPermissions / the Activity Result API. */
    fun permissionsToRequest(): Array<String> = arrayOf(permissionConnect(), permissionScan())

    @SuppressLint("MissingPermission") // guarded by hasPermissions() check below
    fun pairedDevices(): List<BtDevice> {
        if (!hasPermissions() || adapter == null) return emptyList()
        return try {
            adapter.bondedDevices.map { BtDevice(it.name ?: it.address, it.address, true) }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!hasPermissions() || adapter == null) {
            onLog?.invoke("Bluetooth: нет разрешений для поиска устройств", "warn")
            return
        }
        if (adapter.isDiscovering) adapter.cancelDiscovery()

        if (discoveryReceiver == null) {
            discoveryReceiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                ?: return
                            try {
                                onDeviceFound?.invoke(BtDevice(device.name ?: device.address, device.address, false))
                            } catch (_: SecurityException) { /* permission revoked mid-scan */ }
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            if (status == BtStatus.SEARCHING) setStatus(BtStatus.NOT_CONNECTED)
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(discoveryReceiver, filter)
        }

        setStatus(BtStatus.SEARCHING)
        try {
            adapter.startDiscovery()
        } catch (_: SecurityException) {
            onLog?.invoke("Bluetooth: нет разрешения на поиск", "err")
            setStatus(BtStatus.NOT_CONNECTED)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try { adapter?.cancelDiscovery() } catch (_: SecurityException) { /* ignore */ }
        if (status == BtStatus.SEARCHING) setStatus(BtStatus.NOT_CONNECTED)
    }

    /** Connects to [device] over RFCOMM using the well-known PhoneWheel
     *  service UUID. Runs on a background thread since
     *  BluetoothSocket.connect() blocks. Calls back via onStatusChanged as
     *  the attempt progresses/completes. */
    @SuppressLint("MissingPermission")
    fun connect(device: BtDevice) {
        if (!hasPermissions() || adapter == null) {
            onLog?.invoke("Bluetooth: нет разрешений для подключения", "warn")
            setStatus(BtStatus.NOT_CONNECTED)
            return
        }

        disconnect() // tear down any previous connection first

        setStatus(BtStatus.CONNECTING)
        connectThread = Thread {
            try {
                stopDiscoveryQuietly()
                val remote: BluetoothDevice = adapter.getRemoteDevice(device.address)
                val sock = remote.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = sock
                sock.connect() // blocks until connected or throws

                connectedDevice = device
                setStatus(BtStatus.CONNECTED)
                onLog?.invoke("Bluetooth подключен: ${device.name}", "ok")

                readLoop(sock)
            } catch (e: IOException) {
                onLog?.invoke("Bluetooth: ошибка подключения — ${e.message}", "err")
                setStatus(BtStatus.NOT_CONNECTED)
                closeSocketQuietly()
            } catch (e: SecurityException) {
                onLog?.invoke("Bluetooth: нет разрешения — ${e.message}", "err")
                setStatus(BtStatus.NOT_CONNECTED)
                closeSocketQuietly()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscoveryQuietly() {
        try { if (adapter?.isDiscovering == true) adapter.cancelDiscovery() } catch (_: SecurityException) {}
    }

    /** Blocking read loop, runs on the same background thread connect()
     *  started. Reads newline-delimited frames (matching the JSON-per-line
     *  framing WsServer/MainForm already expect) and surfaces a
     *  CONNECTION_LOST status if the stream ends unexpectedly. */
    private fun readLoop(sock: BluetoothSocket) {
        try {
            val input = sock.inputStream.bufferedReader()
            while (status == BtStatus.CONNECTED) {
                val line = input.readLine() ?: break
                if (line.isNotBlank()) onLineReceived?.invoke(line)
            }
        } catch (e: IOException) {
            if (status == BtStatus.CONNECTED) {
                onLog?.invoke("Bluetooth: соединение прервано — ${e.message}", "warn")
                setStatus(BtStatus.CONNECTION_LOST)
            }
        } finally {
            closeSocketQuietly()
        }
    }

    /** Sends one line (a JSON state frame) to the connected PC. No-ops if
     *  not currently connected. */
    fun send(line: String) {
        val sock = socket ?: return
        if (status != BtStatus.CONNECTED) return
        try {
            sock.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            sock.outputStream.flush()
        } catch (_: IOException) {
            setStatus(BtStatus.CONNECTION_LOST)
        }
    }

    fun disconnect() {
        connectThread?.interrupt()
        connectThread = null
        closeSocketQuietly()
        connectedDevice = null
        if (status != BtStatus.NOT_CONNECTED) setStatus(BtStatus.NOT_CONNECTED)
    }

    private fun closeSocketQuietly() {
        try { socket?.close() } catch (_: IOException) { /* ignore */ }
        socket = null
    }

    fun teardown() {
        disconnect()
        discoveryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: IllegalArgumentException) { /* not registered */ }
        }
        discoveryReceiver = null
    }
}
