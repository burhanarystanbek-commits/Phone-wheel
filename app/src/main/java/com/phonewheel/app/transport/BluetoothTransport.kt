package com.phonewheel.app.transport

/**
 * Placeholder Bluetooth (RFCOMM) transport. This class exists now so
 * [ConnectionManager] and the UI can be wired against
 * [TransportKind.BLUETOOTH] end-to-end and compile/run today, before the
 * actual Bluetooth implementation (Feature 1 in the v2.0 plan) lands.
 * Selecting Bluetooth mode currently surfaces a clear "not yet available"
 * state instead of silently doing nothing or crashing.
 *
 * When Bluetooth is implemented, this class's internals get replaced with a
 * real BluetoothManager/BluetoothSocket-based implementation, but the public
 * shape — ITransport — does not need to change, and neither does any calling
 * code in MainActivity.
 */
class BluetoothTransport : ITransport {
    override val kind: TransportKind = TransportKind.BLUETOOTH

    @Volatile
    override var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    override val peerDescription: String? = null

    private var onStateChanged: ((ConnectionState) -> Unit)? = null
    private var onLog: ((String, String) -> Unit)? = null

    override fun setOnStateChanged(cb: (ConnectionState) -> Unit) { onStateChanged = cb }
    override fun setOnLog(cb: (String, String) -> Unit) { onLog = cb }

    override fun connect() {
        onLog?.invoke("Bluetooth ещё не реализован в этой версии", "warn")
        state = ConnectionState.ERROR
        onStateChanged?.invoke(state)
    }

    override fun disconnect() {
        state = ConnectionState.DISCONNECTED
        onStateChanged?.invoke(state)
    }

    override fun send(json: String) {
        // No-op until implemented.
    }
}
