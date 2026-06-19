using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace PhoneWheelPC;

public class WsServer
{
    public const int Port = 27111;

    private HttpListener? _listener;
    private CancellationTokenSource? _cts;

    public event Action<WheelState>? StateReceived;
    public event Action<string>? ClientConnected;
    public event Action? ClientDisconnected;
    public event Action<string>? Log;

    /// <summary>
    /// Starts listening on all interfaces (so phones can reach it over Wi-Fi)
    /// plus loopback (so USB-via-adb-reverse, which connects to 127.0.0.1, works
    /// too). Binding to "+" needs either Administrator rights or a one-time
    /// URL ACL reservation — see TryFixUrlAcl().
    /// </summary>
    public bool Start()
    {
        _cts = new CancellationTokenSource();
        _listener = new HttpListener();
        _listener.Prefixes.Add($"http://+:{Port}/ws/");
        try
        {
            _listener.Start();
        }
        catch (HttpListenerException)
        {
            _listener = null;
            return false;
        }
        _ = AcceptLoop(_cts.Token);
        return true;
    }

    public void Stop()
    {
        _cts?.Cancel();
        try { _listener?.Stop(); } catch { /* ignore */ }
        _listener = null;
    }

    /// <summary>
    /// Runs the one-time "netsh http add urlacl" reservation elevated via UAC,
    /// so future runs of this app don't need Administrator rights.
    /// </summary>
    public static bool TryFixUrlAcl()
    {
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo("netsh",
                $"http add urlacl url=http://+:{Port}/ws/ user=Everyone")
            {
                UseShellExecute = true,
                Verb = "runas",
            };
            var p = System.Diagnostics.Process.Start(psi);
            p?.WaitForExit();
            return p != null && p.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }

    private async Task AcceptLoop(CancellationToken token)
    {
        while (_listener != null && !token.IsCancellationRequested)
        {
            HttpListenerContext ctx;
            try
            {
                ctx = await _listener.GetContextAsync();
            }
            catch
            {
                break; // listener stopped
            }

            if (!ctx.Request.IsWebSocketRequest)
            {
                ctx.Response.StatusCode = 400;
                ctx.Response.Close();
                continue;
            }
            _ = HandleClient(ctx, token);
        }
    }

    private async Task HandleClient(HttpListenerContext ctx, CancellationToken token)
    {
        WebSocket socket;
        try
        {
            var wsCtx = await ctx.AcceptWebSocketAsync(null);
            socket = wsCtx.WebSocket;
        }
        catch (Exception ex)
        {
            Log?.Invoke($"WS accept failed: {ex.Message}");
            return;
        }

        Log?.Invoke($"Телефон подключился: {ctx.Request.RemoteEndPoint}");
        ClientConnected?.Invoke(ctx.Request.RemoteEndPoint?.ToString() ?? "?");

        var buffer = new byte[16 * 1024];
        try
        {
            while (socket.State == WebSocketState.Open && !token.IsCancellationRequested)
            {
                var result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), token);
                if (result.MessageType == WebSocketMessageType.Close) break;
                if (result.MessageType != WebSocketMessageType.Text) continue;

                var json = Encoding.UTF8.GetString(buffer, 0, result.Count);
                try
                {
                    var state = JsonSerializer.Deserialize<WheelState>(json);
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
            ClientDisconnected?.Invoke();
            Log?.Invoke("Телефон отключился");
            try { socket.Dispose(); } catch { /* ignore */ }
        }
    }
}
