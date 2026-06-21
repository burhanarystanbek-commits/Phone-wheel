namespace PhoneWheelPC.Transport;

/// <summary>
/// Adapts <see cref="BluetoothServer"/> (RFCOMM listener) to
/// <see cref="ITransport"/>, mirroring how <see cref="WebSocketTransport"/>
/// adapts <see cref="WsServer"/>. This replaces the placeholder
/// BluetoothTransport that previously always reported "not yet
/// implemented" — <see cref="ConnectionManager"/> doesn't need any changes
/// to use this, since it only depends on <see cref="ITransport"/>.
///
/// Like WebSocketTransport, the PC is the listening side and the phone
/// initiates the connection (after the user picks this PC from their paired
/// devices list on Android) — so Start() here means "begin advertising the
/// RFCOMM service and accepting", not "dial out to a phone".
/// </summary>
public class BluetoothTransport : ITransport
{
    private readonly BluetoothServer _server = new();
    private ConnectionState _state = ConnectionState.Disconnected;
    private string? _peer;

    public TransportKind Kind => TransportKind.Bluetooth;

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

    public BluetoothTransport()
    {
        _server.ClientConnected += name =>
        {
            _peer = name;
            State = ConnectionState.Connected;
        };
        _server.ClientDisconnected += () =>
        {
            _peer = null;
            // Still listening for a reconnect, not fully torn down.
            State = ConnectionState.Reconnecting;
        };
        _server.StateReceived += s => StateReceived?.Invoke(s);
        _server.Log += msg => Log?.Invoke(msg);
    }

    public bool Start()
    {
        if (!_server.IsRadioPresent)
        {
            Log?.Invoke("На этом ПК нет Bluetooth-адаптера.");
            State = ConnectionState.Error;
            return false;
        }
        if (!_server.IsRadioPoweredOn)
        {
            Log?.Invoke("Bluetooth выключен в Windows — включи его и попробуй снова.");
            State = ConnectionState.Error;
            return false;
        }

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
        // Outbound PC -> phone commands aren't part of the current protocol
        // (state flows phone -> PC only, same as WebSocketTransport). Kept
        // as a no-op for ITransport symmetry and to leave room for a future
        // heartbeat/ack frame without changing the interface again.
    }

    public void Dispose() => Stop();
}
