namespace PhoneWheelPC.Transport;

public class ConnectionManager : IDisposable
{
    private ITransport? _active;
    private readonly object _lock = new();

    public static readonly TimeSpan PacketTimeout = TimeSpan.FromSeconds(3);

    private DateTime _lastPacketAt = DateTime.MinValue;
    private DateTime _connectedSince = DateTime.MinValue;
    private int _packetsThisSecond;
    private int _packetRate;
    private int _latencyMs = -1;   // -1 = unknown
    private System.Threading.Timer? _watchdog;
    private string? _connectedPeerName;

    public TransportKind? ActiveKind => _active?.Kind;
    public ConnectionState State => _active?.State ?? ConnectionState.Disconnected;
    public string? PeerDescription => _active?.PeerDescription;
    public int PacketRateHz => _packetRate;
    public int LatencyMs => _latencyMs;

    public TimeSpan? ConnectedDuration =>
        State == ConnectionState.Connected ? DateTime.UtcNow - _connectedSince : null;

    public string ConnectedDurationString
    {
        get
        {
            var d = ConnectedDuration;
            if (d == null) return "—";
            return d.Value.TotalHours >= 1
                ? $"{(int)d.Value.TotalHours}ч {d.Value.Minutes:D2}м {d.Value.Seconds:D2}с"
                : $"{d.Value.Minutes:D2}м {d.Value.Seconds:D2}с";
        }
    }

    public TimeSpan TimeSinceLastPacket =>
        _lastPacketAt == DateTime.MinValue ? TimeSpan.MaxValue : DateTime.UtcNow - _lastPacketAt;

    public event Action<ConnectionState>? StateChanged;
    public event Action<WheelState>? StateReceived;
    public event Action<string>? Log;
    public event Action<int>? PacketRateChanged;
    public event Action<int>? LatencyChanged;   // ms, -1 = unknown

    public ConnectionManager()
    {
        _watchdog = new System.Threading.Timer(_ => Tick(), null,
            TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));
    }

    public bool SwitchTo(TransportKind kind)
    {
        lock (_lock)
        {
            if (_active?.Kind == kind
                && _active.State != ConnectionState.Error
                && _active.State != ConnectionState.Disconnected)
                return true;

            DetachActive();

            ITransport next = kind switch
            {
                TransportKind.WiFi      => new WebSocketTransport(TransportKind.WiFi),
                TransportKind.Usb       => new WebSocketTransport(TransportKind.Usb),
                TransportKind.Bluetooth => new BluetoothTransport(),
                _ => throw new ArgumentOutOfRangeException(nameof(kind)),
            };

            AttachActive(next);
            var ok = next.Start();
            if (!ok) Log?.Invoke($"Не удалось запустить транспорт {kind}.");
            return ok;
        }
    }

    public void Stop() { lock (_lock) DetachActive(); }
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
        try { _active.Stop(); }    catch { }
        try { _active.Dispose(); } catch { }
        _active = null;
        _lastPacketAt = DateTime.MinValue;
        _latencyMs = -1;
        _connectedPeerName = null;
        StateChanged?.Invoke(ConnectionState.Disconnected);
    }

    private void OnTransportStateChanged(ConnectionState s)
    {
        if (s == ConnectionState.Connected)
        {
            _connectedSince = DateTime.UtcNow;
            _connectedPeerName = _active?.PeerDescription;
        }
        StateChanged?.Invoke(s);
    }

    private void OnTransportStateReceived(WheelState s)
    {
        _lastPacketAt = DateTime.UtcNow;
        Interlocked.Increment(ref _packetsThisSecond);

        // Latency estimate: if the phone included a send-timestamp, compute
        // how long ago that was. Requires phone/PC clocks to be roughly in
        // sync (within a few hundred ms), which is almost always true on
        // modern devices that use NTP. We clamp to 0..2000ms to filter wild
        // clock-skew spikes that aren't real latency.
        if (s.Ts > 0)
        {
            var nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var delta = (int)Math.Clamp(nowMs - s.Ts, 0, 2000);
            if (delta != _latencyMs)
            {
                _latencyMs = delta;
                LatencyChanged?.Invoke(_latencyMs);
            }
        }

        StateReceived?.Invoke(s);
    }

    private void Tick()
    {
        _packetRate = Interlocked.Exchange(ref _packetsThisSecond, 0);
        PacketRateChanged?.Invoke(_packetRate);

        if (_active != null
            && _active.State == ConnectionState.Connected
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
