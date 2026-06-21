package com.phonewheel.app.transport

import android.content.Context
import com.phonewheel.app.bluetooth.BluetoothManager
import com.phonewheel.app.bluetooth.BtDevice
import com.phonewheel.app.bluetooth.BtStatus

/**
 * Adapts [BluetoothManager] (RFCOMM connection to a specific, already-chosen
 * device) to [ITransport]. Unlike WebSocketTransport, which listens for the
 * PC to connect to it, Bluetooth here is phone-initiated: the user picks a
 * paired/discovered PC from a device list (see MainActivity's Bluetooth
 * devices screen) and this transport connects out to it.
 *
 * This replaces the placeholder BluetoothTransport that previously always
 * reported "not yet implemented" — ConnectionManager and MainActivity don't
 * need any changes to use this, since both only depend on [ITransport].
 */
class BluetoothTransport(
    context: Context,
    private val targetDevice: BtDevice,
) : ITransport {

    override val kind: TransportKind = TransportKind.BLUETOOTH

    private val manager = BluetoothManager(context)

    @Volatile
    override var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    override val peerDescription: String?
        get() = manager.connectedDevice?.let { "${it.name} (${it.address})" }

    private var onStateChanged: ((ConnectionState) -> Unit)? = null
    private var onLog: ((String, String) -> Unit)? = null

    override fun setOnStateChanged(cb: (ConnectionState) -> Unit) { onStateChanged = cb }
    override fun setOnLog(cb: (String, String) -> Unit) { onLog = cb }

    init {
        manager.setOnStatusChanged { btStatus ->
            state = mapStatus(btStatus)
            onStateChanged?.invoke(state)
        }
        manager.setOnLog { msg, lvl -> onLog?.invoke(msg, lvl) }
        // Inbound traffic (PC -> phone) isn't part of the current protocol,
        // but we still drain the input stream via BluetoothManager's read
        // loop so a closed/reset connection on the PC side is detected
        // promptly instead of leaving a half-open socket.
        manager.setOnLineReceived { /* no inbound commands defined yet */ }
    }

    private fun mapStatus(s: BtStatus): ConnectionState = when (s) {
        BtStatus.NOT_CONNECTED -> ConnectionState.DISCONNECTED
        BtStatus.SEARCHING, BtStatus.CONNECTING -> ConnectionState.CONNECTING
        BtStatus.CONNECTED -> ConnectionState.CONNECTED
        BtStatus.CONNECTION_LOST -> ConnectionState.RECONNECTING
    }

    override fun connect() {
        if (!manager.isSupported()) {
            onLog?.invoke("Это устройство не поддерживает Bluetooth", "err")
            state = ConnectionState.ERROR
            onStateChanged?.invoke(state)
            return
        }
        if (!manager.isEnabled()) {
            onLog?.invoke("Bluetooth выключен — включи его в настройках телефона", "warn")
            state = ConnectionState.ERROR
            onStateChanged?.invoke(state)
            return
        }
        if (!manager.hasPermissions()) {
            onLog?.invoke("Нет разрешений Bluetooth", "warn")
            state = ConnectionState.ERROR
            onStateChanged?.invoke(state)
            return
        }
        manager.connect(targetDevice)
    }

    override fun disconnect() {
        manager.teardown()
    }

    override fun send(json: String) {
        manager.send(json)
    }
}
