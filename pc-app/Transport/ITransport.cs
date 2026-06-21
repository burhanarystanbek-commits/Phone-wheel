namespace PhoneWheelPC.Transport;

/// <summary>
/// Lifecycle state of any transport, reported the same way regardless of
/// whether the underlying connection is Wi-Fi, USB (adb reverse + WebSocket),
/// or Bluetooth (RFCOMM). UI code switches on this enum instead of asking
/// "is this a WebSocketTransport or a BluetoothTransport?".
/// </summary>
public enum ConnectionState
{
    Disconnected,
    Listening,      // server socket open, waiting for the phone to connect
    Connecting,      // actively trying to reach a specific device (Bluetooth)
    Connected,
    Reconnecting,
    Error,
}

public enum TransportKind
{
    WiFi,
    Usb,
    Bluetooth,
}

/// <summary>
/// Everything the rest of the app needs from a connection to the phone.
/// ConnectionManager talks only to this interface — it never knows whether
/// bytes are going out over a raw TCP WebSocket frame or an RFCOMM Bluetooth
/// socket. Adding a new transport later (e.g. USB-serial) means implementing
/// this interface once; nothing else in the app changes.
/// </summary>
public interface ITransport : IDisposable
{
    TransportKind Kind { get; }
    ConnectionState State { get; }

    /// <summary>Human-readable identity of the connected peer, e.g. an IP
    /// address for Wi-Fi/USB or a Bluetooth device name/MAC for BT. Null
    /// when nothing is connected yet.</summary>
    string? PeerDescription { get; }

    /// <summary>Fired whenever <see cref="State"/> changes.</summary>
    event Action<ConnectionState>? StateChanged;

    /// <summary>Fired once per inbound WheelState frame.</summary>
    event Action<WheelState>? StateReceived;

    /// <summary>Fired for connection-relevant log lines (connect, disconnect,
    /// errors) that the UI may want to show in its log panel.</summary>
    event Action<string>? Log;

    /// <summary>Starts whatever this transport needs to start (a listen
    /// socket for Wi-Fi/USB, a device connection attempt for Bluetooth).
    /// Returns false if startup failed outright (e.g. port already in use).</summary>
    bool Start();

    /// <summary>Stops the transport and releases its resources. Safe to call
    /// even if Start() was never called or already stopped.</summary>
    void Stop();

    /// <summary>Sends a state/command frame to the connected peer, if any.
    /// No-ops silently if nothing is connected — callers don't need to check
    /// State before calling Send.</summary>
    void Send(string json);
}
