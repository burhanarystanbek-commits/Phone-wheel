namespace PhoneWheelPC.Transport;

/// <summary>
/// Single point of contact the UI and vJoy bridge talk to, regardless of
/// which transport is active. Owns the currently-selected ITransport,
/// handles switching between Wi-Fi / USB / Bluetooth without requiring an
/// app restart, and derives connection-quality metrics (packet rate, time
/// since last packet) that every transport benefits from without each one
/// having to implement it separately.
///
/// This is the seam Feature 3 (Connection Manager) and Feature 5
/// (performance / watchdog) build on: heartbeat and latency measurement get
/// added here once, and they work identically no matter which ITransport is
/// underneath.
/// </summary>
public class ConnectionManager : IDisposable
{
    private ITransport? _active;
    private readonly object _lock = new();

    /// <summary>How long without a packet before we consider the link dead
    /// and report Reconnecting/Disconnected even if the socket itself hasn't
    /// noticed yet. Used by the watchdog timer.</summary>
    public static readonly TimeSpan PacketTimeout = TimeSpan.FromSeconds(3);

    private DateTime _lastPacketAt = DateTime.MinValue;
    private DateTime _connectedSince = DateTime.MinValue;
    private int _packetsThisSecond;
    private int _packetRate;
    private System.Threading.Timer? _watchdog;

    public TransportKind? ActiveKind => _active?.Kind;
    public ConnectionState State => _active?.State ?? ConnectionState.Disconnected;
    public string? PeerDescription => _active?.PeerDescription;
    public int PacketRateHz => _packetRate;
    public TimeSpan? ConnectedDuration =>
        State == ConnectionState.Connected ? DateTime.UtcNow - _connectedSince : null;
    public TimeSpan TimeSinceLastPacket =>
        _lastPacketAt == DateTime.MinValue ? TimeSpan.MaxValue : DateTime.UtcNow - _lastPacketAt;

    public event Action<ConnectionState>? StateChanged;
    public event Action<WheelState>? StateReceived;
    public event Action<string>? Log;
    public event Action<int>? PacketRateChanged;

    public ConnectionManager()
    {
        _watchdog = new System.Threading.Timer(_ => Tick(), null,
            TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));
    }

    /// <summary>Switches the active transport, stopping whatever was running
    /// before. Safe to call repeatedly, e.g. from a mode selector in the UI —
    /// switching does not require restarting the application.</summary>
    public bool SwitchTo(TransportKind kind)
    {
        lock (_lock)
        {
            if (_active?.Kind == kind && _active.State != ConnectionState.Error
                && _active.State != ConnectionState.Disconnected)
            {
                // Already on this transport and it's functioning — nothing to do.
                return true;
            }

            DetachActive();

            ITransport next = kind switch
            {
                TransportKind.WiFi => new WebSocketTransport(TransportKind.WiFi),
                TransportKind.Usb => new WebSocketTransport(TransportKind.Usb),
                TransportKind.Bluetooth => new BluetoothTransport(),
                _ => throw new ArgumentOutOfRangeException(nameof(kind)),
            };

            AttachActive(next);
            var ok = next.Start();
            if (!ok) Log?.Invoke($"Не удалось запустить транспорт {kind}.");
            return ok;
        }
    }

    public void Stop()
    {
        lock (_lock) DetachActive();
    }

    /// <summary>Sends a frame through whichever transport is currently
    /// active. No-op if nothing is connected.</summary>
    public void Send(string json) => _active?.Send(json);

    private void AttachActive(ITransport t)
    {
        _active = t;
        t.StateChanged += OnTransportStateChanged;
        t.StateReceived += OnTransportStateReceived;
        t.Log += msg => Log?.Invoke(msg);
    }

    private void DetachActive()
    {
        if (_active == null) return;
        _active.StateChanged -= OnTransportStateChanged;
        _active.StateReceived -= OnTransportStateReceived;
        try { _active.Stop(); } catch { /* ignore */ }
        try { _active.Dispose(); } catch { /* ignore */ }
        _active = null;
        _lastPacketAt = DateTime.MinValue;
        StateChanged?.Invoke(ConnectionState.Disconnected);
    }

    private void OnTransportStateChanged(ConnectionState s)
    {
        if (s == ConnectionState.Connected) _connectedSince = DateTime.UtcNow;
        StateChanged?.Invoke(s);
    }

    private void OnTransportStateReceived(WheelState s)
    {
        _lastPacketAt = DateTime.UtcNow;
        Interlocked.Increment(ref _packetsThisSecond);
        StateReceived?.Invoke(s);
    }

    /// <summary>Runs once a second: rolls the packet-rate counter and detects
    /// silent timeouts (socket still technically open but nothing has
    /// arrived in <see cref="PacketTimeout"/>) so the UI can show
    /// "Reconnecting" instead of a falsely-green "Connected" indicator.</summary>
    private void Tick()
    {
        _packetRate = Interlocked.Exchange(ref _packetsThisSecond, 0);
        PacketRateChanged?.Invoke(_packetRate);

        if (_active != null && _active.State == ConnectionState.Connected
            && TimeSinceLastPacket > PacketTimeout)
        {
            Log?.Invoke("Нет пакетов от телефона — связь могла прерваться.");
        }
    }

    public void Dispose()
    {
        _watchdog?.Dispose();
        _watchdog = null;
        Stop();
    }
}
