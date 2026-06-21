package com.phonewheel.app.transport

/**
 * Lifecycle state of any transport, reported the same way regardless of
 * whether the underlying connection is Wi-Fi, USB (loopback WebSocket via
 * adb reverse) or Bluetooth (RFCOMM). The UI switches on this enum instead
 * of asking "is this a WebSocketTransport or a BluetoothTransport?".
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

enum class TransportKind {
    WIFI,
    USB,
    BLUETOOTH,
}

/**
 * Everything the rest of the app needs from a connection to the PC.
 * ConnectionManager and MainActivity talk only to this interface — neither
 * needs to know whether bytes are going out over a WebSocket frame or an
 * RFCOMM Bluetooth socket. Adding a new transport later means implementing
 * this interface once; nothing else in the app changes.
 */
interface ITransport {
    val kind: TransportKind
    val state: ConnectionState

    /** Human-readable identity of the connected peer (PC), e.g. its IP for
     *  Wi-Fi/USB or device name for Bluetooth. Null when not connected. */
    val peerDescription: String?

    fun setOnStateChanged(cb: (ConnectionState) -> Unit)
    fun setOnLog(cb: (String, String) -> Unit) // message, level ("ok"/"warn"/"err")

    /** Starts connecting. Non-blocking — state changes arrive via the
     *  onStateChanged callback. */
    fun connect()

    /** Stops the transport and releases its resources. Safe to call even if
     *  connect() was never called or it's already stopped. */
    fun disconnect()

    /** Sends a JSON frame to the PC, if currently connected. No-ops silently
     *  otherwise — callers don't need to check state before calling send. */
    fun send(json: String)
}
