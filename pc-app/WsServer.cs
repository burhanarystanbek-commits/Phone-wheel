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
        // All interfaces (needed for Wi-Fi). Requires admin or a URL ACL.
        if (TryStart($"http://+:{Port}/ws/"))
        {
            LocalOnly = false;
        }
        // Fallback: loopback only — USB via adb reverse still works without admin.
        else if (TryStart($"http://localhost:{Port}/ws/") || TryStart($"http://127.0.0.1:{Port}/ws/"))
        {
            LocalOnly = true;
            Log?.Invoke("Wi-Fi недоступен: нет прав на порт. Нажми «Настроить доступ» (один раз, нужен UAC). USB работает.");
        }
        else
        {
            return false;
        }
        _ = AcceptLoop(_cts.Token);
        return true;
    }

    /// <summary>True when only 127.0.0.1 is bound (no URL ACL yet) — Wi-Fi
    /// phones can't reach us in that state.</summary>
    public bool LocalOnly { get; private set; }

    private bool TryStart(string prefix)
    {
        var l = new HttpListener();
        l.Prefixes.Add(prefix);
        try { l.Start(); _listener = l; return true; }
        catch (HttpListenerException) { try { l.Close(); } catch { } return false; }
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
            // "user=Everyone" fails on non-English Windows (it's "Все" in
            // Russian) — use the language-neutral SDDL for Everyone instead.
            // Also open the port in Windows Firewall, otherwise the phone's
            // Wi-Fi connection is silently dropped.
            var cmd =
                $"/c netsh http delete urlacl url=http://+:{Port}/ws/ & " +
                $"netsh http add urlacl url=http://+:{Port}/ws/ sddl=D:(A;;GX;;;WD) & " +
                $"netsh advfirewall firewall delete rule name=PhoneWheel & " +
                $"netsh advfirewall firewall add rule name=PhoneWheel dir=in action=allow protocol=TCP localport={Port} profile=any";
            var psi = new System.Diagnostics.ProcessStartInfo("cmd.exe", cmd)
            {
                UseShellExecute = true,
                Verb = "runas",
            };
            var p = System.Diagnostics.Process.Start(psi);
            p?.WaitForExit();
            return p != null;
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
                var count = 0;
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer, count, buffer.Length - count), token);
                    count += result.Count;
                } while (!result.EndOfMessage && count < buffer.Length);
                if (result.MessageType == WebSocketMessageType.Close) break;
                if (result.MessageType != WebSocketMessageType.Text || !result.EndOfMessage) continue;

                var json = Encoding.UTF8.GetString(buffer, 0, count);
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
