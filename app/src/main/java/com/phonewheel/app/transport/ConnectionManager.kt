package com.phonewheel.app.transport

/**
 * Single point of contact MainActivity talks to, regardless of which
 * transport is active. Owns the currently-selected [ITransport] and handles
 * switching between Wi-Fi / USB / Bluetooth without requiring an app
 * restart — UI code calls [switchTo] and [send]/[disconnect] and never
 * touches WebSocketTransport or BluetoothTransport directly.
 */
class ConnectionManager {

    private var active: ITransport? = null

    var kind: TransportKind? = null
        private set

    val state: ConnectionState get() = active?.state ?: ConnectionState.DISCONNECTED
    val peerDescription: String? get() = active?.peerDescription

    private var onStateChanged: ((ConnectionState) -> Unit)? = null
    private var onLog: ((String, String) -> Unit)? = null

    fun setOnStateChanged(cb: (ConnectionState) -> Unit) { onStateChanged = cb }
    fun setOnLog(cb: (String, String) -> Unit) { onLog = cb }

    /** Replaces the active transport and connects it. [factory] builds the
     *  concrete ITransport for the requested kind (so MainActivity supplies
     *  the URL/hello-payload it already knows how to build, keeping this
     *  class free of any connection-string logic). */
    fun switchTo(newKind: TransportKind, factory: (TransportKind) -> ITransport) {
        active?.disconnect()
        val t = factory(newKind)
        t.setOnStateChanged { s -> onStateChanged?.invoke(s) }
        t.setOnLog { msg, lvl -> onLog?.invoke(msg, lvl) }
        active = t
        kind = newKind
        t.connect()
    }

    fun disconnect() {
        active?.disconnect()
    }

    fun send(json: String) {
        active?.send(json)
    }
}
