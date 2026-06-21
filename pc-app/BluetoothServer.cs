using InTheHand.Net;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;
using System.Text;

namespace PhoneWheelPC;

/// <summary>
/// Accepts incoming RFCOMM Bluetooth connections from the phone. Unlike
/// Wi-Fi/USB (where the PC is an HTTP/WebSocket server the phone reaches
/// over TCP), Bluetooth here works the other way: the phone, after the user
/// picks this PC from its paired devices list, opens an RFCOMM socket to the
/// well-known PhoneWheel service UUID that this class advertises. So this
/// class is a *server* (it listens and accepts), even though the phone is
/// the one initiating the socket connect() call — that's normal for RFCOMM:
/// a service advertises a UUID and listens, a client looks it up and
/// connects.
///
/// Framing matches BluetoothManager.kt on the Android side: newline-
/// delimited JSON, one WheelState object per line.
/// </summary>
public class BluetoothServer
{
    /// <summary>Must match BluetoothManager.SERVICE_UUID in
    /// BluetoothManager.kt exactly — this is the rendezvous point between
    /// the two apps, not a secret.</summary>
    public static readonly Guid ServiceUuid = new("8ce255c0-200a-11e0-ac64-0800200c9a66");
    public const string ServiceName = "PhoneWheel";

    private BluetoothListener? _listener;
    private BluetoothClient? _activeClient;
    private CancellationTokenSource? _cts;

    public event Action<WheelState>? StateReceived;
    public event Action<string>? ClientConnected; // device name/address
    public event Action? ClientDisconnected;
    public event Action<string>? Log;

    public bool IsRadioPresent => BluetoothRadio.PrimaryRadio != null;
    public bool IsRadioPoweredOn => BluetoothRadio.PrimaryRadio?.Mode != RadioMode.PowerOff;

    /// <summary>Starts advertising the PhoneWheel RFCOMM service and
    /// accepting connections. Returns false if no Bluetooth radio is present
    /// or it could not be started (radio off, drivers missing, etc).</summary>
    public bool Start()
    {
        if (BluetoothRadio.PrimaryRadio == null)
        {
            Log?.Invoke("Bluetooth-адаптер не найден на этом ПК.");
            return false;
        }
        if (BluetoothRadio.PrimaryRadio.Mode == RadioMode.PowerOff)
        {
            Log?.Invoke("Bluetooth выключен на этом ПК. Включи его в настройках Windows.");
            return false;
        }

        try
        {
            _cts = new CancellationTokenSource();
            _listener = new BluetoothListener(ServiceUuid) { ServiceName = ServiceName };
            _listener.Start();
            Log?.Invoke($"Bluetooth-сервер слушает (UUID {ServiceUuid}).");
            _ = AcceptLoop(_cts.Token);
            return true;
        }
        catch (Exception ex)
        {
            Log?.Invoke($"Не удалось запустить Bluetooth-сервер: {ex.Message}");
            _listener = null;
            return false;
        }
    }

    public void Stop()
    {
        _cts?.Cancel();
        try { _activeClient?.Close(); } catch { /* ignore */ }
        try { _listener?.Stop(); } catch { /* ignore */ }
        _listener = null;
        _activeClient = null;
    }

    private async Task AcceptLoop(CancellationToken token)
    {
        while (_listener != null && !token.IsCancellationRequested)
        {
            BluetoothClient client;
            try
            {
                client = await Task.Run(() => _listener.AcceptBluetoothClient(), token);
            }
            catch
            {
                break; // listener stopped
            }

            // Only one phone drives the wheel at a time — if a new client
            // connects, replace whatever was there before rather than
            // juggling multiple simultaneous inputs.
            try { _activeClient?.Close(); } catch { /* ignore */ }
            _activeClient = client;

            var remoteName = SafeDeviceName(client);
            Log?.Invoke($"Bluetooth подключен: {remoteName}");
            ClientConnected?.Invoke(remoteName);

            _ = HandleClient(client, token);
        }
    }

    private static string SafeDeviceName(BluetoothClient client)
    {
        try
        {
            var ep = client.RemoteEndPoint as BluetoothEndPoint;
            return ep?.Device.DeviceName ?? ep?.Address.ToString() ?? "неизвестное устройство";
        }
        catch
        {
            return "неизвестное устройство";
        }
    }

    private async Task HandleClient(BluetoothClient client, CancellationToken token)
    {
        try
        {
            using var stream = client.GetStream();
            using var reader = new StreamReader(stream, Encoding.UTF8);

            while (client.Connected && !token.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(token);
                if (line == null) break; // remote closed
                if (string.IsNullOrWhiteSpace(line)) continue;

                try
                {
                    var state = System.Text.Json.JsonSerializer.Deserialize<WheelState>(line);
                    if (state != null) StateReceived?.Invoke(state);
                }
                catch
                {
                    // ignore malformed frames, keep the connection alive
                }
            }
        }
        catch
        {
            // connection dropped
        }
        finally
        {
            if (ReferenceEquals(_activeClient, client)) _activeClient = null;
            ClientDisconnected?.Invoke();
            Log?.Invoke("Bluetooth-устройство отключилось");
            try { client.Close(); } catch { /* ignore */ }
        }
    }
}
