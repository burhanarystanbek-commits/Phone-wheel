package com.phonewheel.app.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Adapts the existing OkHttp WebSocket client to [ITransport]. Used for both
 * Wi-Fi mode and USB mode — the only difference between them is the target
 * host (127.0.0.1 tunneled over `adb reverse` for USB, the PC's LAN IP for
 * Wi-Fi) and what gets reported as [kind]; the connection code itself is
 * identical.
 */
class WebSocketTransport(
    override val kind: TransportKind,
    private val url: String,
    private val helloPayload: () -> String,
) : ITransport {

    private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    private var socket: WebSocket? = null

    @Volatile
    override var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    override var peerDescription: String? = null
        private set

    private var onStateChanged: ((ConnectionState) -> Unit)? = null
    private var onLog: ((String, String) -> Unit)? = null

    override fun setOnStateChanged(cb: (ConnectionState) -> Unit) { onStateChanged = cb }
    override fun setOnLog(cb: (String, String) -> Unit) { onLog = cb }

    private fun setState(s: ConnectionState) {
        state = s
        onStateChanged?.invoke(s)
    }

    override fun connect() {
        setState(ConnectionState.CONNECTING)
        val req = Request.Builder().url(url).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                peerDescription = url
                setState(ConnectionState.CONNECTED)
                try { ws.send(helloPayload()) } catch (_: Exception) {}
                onLog?.invoke("Подключено к $url", "ok")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                peerDescription = null
                setState(ConnectionState.ERROR)
                onLog?.invoke("Ошибка: ${t.message ?: "?"}", "err")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                peerDescription = null
                setState(ConnectionState.DISCONNECTED)
                onLog?.invoke("Отключено", "warn")
            }
        })
    }

    override fun disconnect() {
        socket?.close(1000, "user")
        socket = null
        peerDescription = null
        setState(ConnectionState.DISCONNECTED)
    }

    override fun send(json: String) {
        if (state != ConnectionState.CONNECTED) return
        try { socket?.send(json) } catch (_: Exception) {}
    }
}
