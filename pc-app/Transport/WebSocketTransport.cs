namespace PhoneWheelPC.Transport;

/// <summary>
/// Adapts the existing <see cref="WsServer"/> (HttpListener-based WebSocket
/// server) to <see cref="ITransport"/>. Used for both Wi-Fi mode and USB mode
/// — the only difference between them is how the phone's packets physically
/// reach this PC's port 27111 (LAN routing for Wi-Fi, `adb reverse` tunnel
/// for USB); the server code itself is identical, which is why one
/// WebSocketTransport class serves both <see cref="TransportKind.WiFi"/> and
/// <see cref="TransportKind.Usb"/> (the caller picks which Kind to report).
/// </summary>
public class WebSocketTransport : ITransport
{
    private readonly WsServer _server = new();
    private ConnectionState _state = ConnectionState.Disconnected;
    private string? _peer;

    public TransportKind Kind { get; }

    public ConnectionState State
    {
        get => _state;
        private set
        {
            if (_state == value) return;
            _state = value;
            StateChanged?.Invoke(_state);
        }
    }

    public string? PeerDescription => _peer;

    public event Action<ConnectionState>? StateChanged;
    public event Action<WheelState>? StateReceived;
    public event Action<string>? Log;

    public WebSocketTransport(TransportKind kind)
    {
        if (kind != TransportKind.WiFi && kind != TransportKind.Usb)
            throw new ArgumentException("WebSocketTransport only supports WiFi or Usb kind", nameof(kind));
        Kind = kind;

        _server.ClientConnected += addr =>
        {
            _peer = addr;
            State = ConnectionState.Connected;
        };
        _server.ClientDisconnected += () =>
        {
            _peer = null;
            State = ConnectionState.Listening;
        };
        _server.StateReceived += s => StateReceived?.Invoke(s);
        _server.Log += msg => Log?.Invoke(msg);
    }

    public bool Start()
    {
        var ok = _server.Start();
        State = ok ? ConnectionState.Listening : ConnectionState.Error;
        return ok;
    }

    public void Stop()
    {
        _server.Stop();
        _peer = null;
        State = ConnectionState.Disconnected;
    }

    public void Send(string json)
    {
        // The phone is the WebSocket client and the PC is the server in this
        // transport, so outbound "commands to the phone" aren't part of the
        // current protocol — state flows phone -> PC only. This method exists
        // to satisfy ITransport for symmetry with BluetoothTransport (which
        // is bidirectional) and is a no-op placeholder for now.
    }

    /// <summary>Exposes the static port-ACL helper so the UI can still offer
    /// the existing "Настроить доступ" button without depending on WsServer
    /// directly.</summary>
    public static bool TryFixUrlAcl() => WsServer.TryFixUrlAcl();

    public void Dispose() => Stop();
}
