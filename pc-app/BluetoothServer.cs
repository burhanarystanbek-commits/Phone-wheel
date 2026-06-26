using System.Net.Sockets;
using System.Text;
using PhoneWheelPC.Bluetooth;

namespace PhoneWheelPC;

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
            try
            {
                // AcceptAsync on AF_BTH works — but RemoteEndPoint throws
                // NotSupportedException on non-IP sockets in .NET, so we
                // don't call it. Use a fixed label instead.
                client = await _listenSocket.AcceptAsync(token);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                Log?.Invoke($"AcceptLoop ошибка: {ex.Message}");
                break;
            }

            SafeClose(_clientSocket);
            _clientSocket = client;

            Log?.Invoke("Bluetooth: устройство подключилось.");
            ClientConnected?.Invoke("Bluetooth-устройство");

            _ = HandleClient(client, token);
        }
    }

    private async Task HandleClient(Socket client, CancellationToken token)
    {
        Log?.Invoke("Bluetooth: начало чтения данных...");
        try
        {
            // AF_BTH сокеты в .NET не поддерживают NetworkStream напрямую
            // (конструктор проверяет AddressFamily и может бросить).
            // Читаем вручную через Socket.ReceiveAsync в буфер, собирая строки.
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
                catch (Exception ex)
                {
                    Log?.Invoke($"Bluetooth: ошибка чтения — {ex.Message}");
                    break;
                }

                if (received == 0)
                {
                    Log?.Invoke("Bluetooth: соединение закрыто телефоном (EOF).");
                    break;
                }

                // Append received bytes as UTF-8 text and split on newlines.
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
                    catch { /* ignore malformed frame */ }
                }

                remainder.Clear();
                if (start < text.Length)
                    remainder.Append(text.Substring(start));
            }
        }
        catch (Exception ex)
        {
            Log?.Invoke($"Bluetooth HandleClient: {ex.Message}");
        }
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
