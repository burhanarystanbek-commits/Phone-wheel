namespace PhoneWheelPC.Transport;

/// <summary>
/// Adapts <see cref="BluetoothServer"/> (RFCOMM listener) to
/// <see cref="ITransport"/>. This replaces the placeholder BluetoothTransport
/// that previously always reported "not yet implemented".
/// The PC is the listening side; the phone initiates the RFCOMM connect.
/// Start() advertises the service and waits for the phone to connect.
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
            State = ConnectionState.Reconnecting;
        };
        _server.StateReceived += s => StateReceived?.Invoke(s);
        _server.Log += msg => Log?.Invoke(msg);
    }

    public bool Start()
    {
        if (!_server.IsRadioPresent)
        {
            Log?.Invoke("Bluetooth-адаптер не найден на этом ПК или Bluetooth выключен в Windows.");
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

    public void Send(string json) { /* state flows phone -> PC only */ }

    public void Dispose() => Stop();
}
