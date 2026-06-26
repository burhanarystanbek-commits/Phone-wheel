using System.Net.Sockets;
using System.Text;
using PhoneWheelPC.Bluetooth;

namespace PhoneWheelPC;

public class BluetoothServer
{
    public static readonly Guid   ServiceUuid = new("8ce255c0-200a-11e0-ac64-0800200c9a66");
    public const           string ServiceName = "PhoneWheel";

    private Socket? _listenSocket;
    private Socket? _clientSocket;
    private CancellationTokenSource? _cts;

    public event Action<WheelState>? StateReceived;
    public event Action<string>?     ClientConnected;
    public event Action?             ClientDisconnected;
    public event Action<string>?     Log;

    public bool IsRadioPresent
    {
        get { var ok = Win32Bluetooth.TryCreateRfcommSocket(out var s, out _); s?.Dispose(); return ok; }
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

            // Bind + register SDP so Android can discover us.
            Win32Bluetooth.BindAndRegisterSdp(_listenSocket, ServiceUuid, ServiceName);
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
            catch (OperationCanceledException) { break; }
            catch (Exception ex) { Log?.Invoke($"Accept: {ex.Message}"); break; }

            SafeClose(_clientSocket);
            _clientSocket = client;
            Log?.Invoke("Bluetooth: устройство подключилось.");
            ClientConnected?.Invoke("Bluetooth-устройство");
            _ = HandleClient(client, token);
        }
    }

    private async Task HandleClient(Socket client, CancellationToken token)
    {
        try
        {
            var buffer    = new byte[4096];
            var remainder = new StringBuilder();

            while (!token.IsCancellationRequested)
            {
                int received;
                try
                {
                    received = await client.ReceiveAsync(
                        new ArraySegment<byte>(buffer), SocketFlags.None, token);
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex) { Log?.Invoke($"Bluetooth чтение: {ex.Message}"); break; }

                if (received == 0) { Log?.Invoke("Bluetooth: EOF."); break; }

                remainder.Append(Encoding.UTF8.GetString(buffer, 0, received));
                var text = remainder.ToString();
                var start = 0;
                for (var i = 0; i < text.Length; i++)
                {
                    if (text[i] != '\n') continue;
                    var line = text.Substring(start, i - start).Trim();
                    start = i + 1;
                    if (string.IsNullOrEmpty(line)) continue;
                    try
                    {
                        var state = System.Text.Json.JsonSerializer.Deserialize<WheelState>(line);
                        if (state != null) StateReceived?.Invoke(state);
                    }
                    catch { }
                }
                remainder.Clear();
                if (start < text.Length) remainder.Append(text.Substring(start));
            }
        }
        catch (Exception ex) { Log?.Invoke($"HandleClient: {ex.Message}"); }
        finally
        {
            if (ReferenceEquals(_clientSocket, client)) _clientSocket = null;
            ClientDisconnected?.Invoke();
            Log?.Invoke("Bluetooth: устройство отключилось.");
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
