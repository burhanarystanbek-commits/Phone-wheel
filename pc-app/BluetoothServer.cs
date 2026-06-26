using System.Net.Sockets;
using System.Text;
using PhoneWheelPC.Bluetooth;

namespace PhoneWheelPC;

/// <summary>
/// Accepts incoming RFCOMM Bluetooth connections from the phone.
/// Uses Win32Bluetooth.BindRfcommServer() (direct ws2_32!bind P/Invoke)
/// instead of Socket.Bind(EndPoint) — the .NET Socket.Bind pipeline
/// mangles SOCKADDR_BTH bytes and produces WSAEADDRNOTAVAIL (10049).
/// </summary>
public class BluetoothServer
{
    public static readonly Guid ServiceUuid =
        new("8ce255c0-200a-11e0-ac64-0800200c9a66");

    private Socket? _listenSocket;
    private Socket? _clientSocket;
    private CancellationTokenSource? _cts;

    public event Action<WheelState>? StateReceived;
    public event Action<string>?     ClientConnected;
    public event Action?             ClientDisconnected;
    public event Action<string>?     Log;

    public bool IsRadioPresent
    {
        get
        {
            var ok = Win32Bluetooth.TryCreateRfcommSocket(out var s, out _);
            s?.Dispose();
            return ok;
        }
    }

    public bool Start()
    {
        if (!Win32Bluetooth.TryCreateRfcommSocket(out var listenSock, out var err))
        {
            Log?.Invoke($"Bluetooth недоступен: {err}. Убедись что Bluetooth включён в Windows.");
            return false;
        }

        try
        {
            _listenSocket = listenSock!;

            // Direct P/Invoke bind — bypasses Socket.Bind(EndPoint) which
            // corrupts SOCKADDR_BTH and causes WSAEADDRNOTAVAIL (10049).
            Win32Bluetooth.BindRfcommServer(_listenSocket, ServiceUuid);
            _listenSocket.Listen(1);

            _cts = new CancellationTokenSource();
            Log?.Invoke($"Bluetooth-сервер слушает (UUID {ServiceUuid}).");
            _ = AcceptLoop(_cts.Token);
            return true;
        }
        catch (Exception ex)
        {
            Log?.Invoke($"Ошибка запуска Bluetooth-сервера: {ex.Message}");
            listenSock?.Dispose();
            _listenSocket = null;
            return false;
        }
    }

    public void Stop()
    {
        _cts?.Cancel();
        SafeClose(_clientSocket); _clientSocket = null;
        SafeClose(_listenSocket); _listenSocket = null;
    }

    private async Task AcceptLoop(CancellationToken token)
    {
        while (!token.IsCancellationRequested && _listenSocket != null)
        {
            Socket client;
            try   { client = await _listenSocket.AcceptAsync(token); }
            catch { break; }

            SafeClose(_clientSocket);
            _clientSocket = client;

            var peerName = client.RemoteEndPoint?.ToString() ?? "телефон";
            Log?.Invoke($"Bluetooth подключен: {peerName}");
            ClientConnected?.Invoke(peerName);
            _ = HandleClient(client, token);
        }
    }

    private async Task HandleClient(Socket client, CancellationToken token)
    {
        try
        {
            var ns = new NetworkStream(client, ownsSocket: false);
            using var reader = new StreamReader(ns, Encoding.UTF8);
            while (client.Connected && !token.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(token);
                if (line == null) break;
                if (string.IsNullOrWhiteSpace(line)) continue;
                try
                {
                    var state = System.Text.Json.JsonSerializer.Deserialize<WheelState>(line);
                    if (state != null) StateReceived?.Invoke(state);
                }
                catch { /* ignore malformed frames */ }
            }
        }
        catch { /* connection dropped */ }
        finally
        {
            if (ReferenceEquals(_clientSocket, client)) _clientSocket = null;
            ClientDisconnected?.Invoke();
            Log?.Invoke("Bluetooth-устройство отключилось.");
            SafeClose(client);
        }
    }

    private static void SafeClose(Socket? s)
    {
        try { s?.Shutdown(SocketShutdown.Both); } catch { }
        try { s?.Close(); }                      catch { }
        s?.Dispose();
    }
}
