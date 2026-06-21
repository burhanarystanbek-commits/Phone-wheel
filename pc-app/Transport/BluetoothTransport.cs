namespace PhoneWheelPC.Transport;

/// <summary>
/// Placeholder Bluetooth (RFCOMM) transport. This class exists now so that
/// <see cref="ConnectionManager"/> and the UI can be wired against
/// <see cref="TransportKind.Bluetooth"/> end-to-end and compile/run today,
/// before the actual Bluetooth implementation (Feature 1 in the v2.0 plan)
/// lands. Selecting Bluetooth mode currently surfaces a clear "not yet
/// available" state instead of silently doing nothing or crashing.
///
/// When Bluetooth is implemented, this class's internals get replaced with
/// a real RFCOMM server (System.Net.Sockets / 32feet.NET or
/// Windows.Devices.Bluetooth APIs) but the public shape — ITransport — does
/// not need to change, and neither does any calling code.
/// </summary>
public class BluetoothTransport : ITransport
{
    private ConnectionState _state = ConnectionState.Disconnected;

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

    public string? PeerDescription => null;

    public event Action<ConnectionState>? StateChanged;
    public event Action<WheelState>? StateReceived;
    public event Action<string>? Log;

    public bool Start()
    {
        Log?.Invoke("Bluetooth: ещё не реализован в этой версии.");
        State = ConnectionState.Error;
        return false;
    }

    public void Stop()
    {
        State = ConnectionState.Disconnected;
    }

    public void Send(string json)
    {
        // No-op until implemented.
    }

    public void Dispose() => Stop();
}
