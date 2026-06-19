using System.Diagnostics;

namespace PhoneWheelPC;

/// <summary>
/// USB mode relies on Android's adb "reverse" port forwarding: the phone
/// connects to its own 127.0.0.1:27111, and adb tunnels that over the USB
/// cable to this same port on the PC. This requires:
///   1. Android platform-tools (adb.exe) installed and on PATH on the PC.
///   2. USB debugging enabled on the phone (Developer Options) and the
///      phone authorized for this PC (the "Allow USB debugging?" prompt).
/// We can't install adb for the user (it's not safe to silently fetch and
/// run a binary), but we can drive it once it's present.
/// </summary>
public static class AdbHelper
{
    public static bool AdbAvailable()
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("adb", "version")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            });
            p?.WaitForExit(3000);
            return p != null && p.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }

    public static (bool ok, string message) SetupReverse()
    {
        if (!AdbAvailable())
            return (false, "adb.exe не найден в PATH. Установи Android platform-tools и попробуй снова.");

        try
        {
            using var devices = Process.Start(new ProcessStartInfo("adb", "devices")
            {
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            });
            var output = devices?.StandardOutput.ReadToEnd() ?? "";
            devices?.WaitForExit(3000);
            var deviceLines = output.Split('\n')
                .Skip(1)
                .Where(l => l.Contains("\tdevice"))
                .ToList();
            if (deviceLines.Count == 0)
                return (false, "Телефон не виден по USB. Включи 'USB-отладку' и подтверди подключение на телефоне.");

            using var reverse = Process.Start(new ProcessStartInfo(
                "adb", $"reverse tcp:{WsServer.Port} tcp:{WsServer.Port}")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            });
            var err = reverse?.StandardError.ReadToEnd() ?? "";
            reverse?.WaitForExit(3000);
            if (reverse != null && reverse.ExitCode == 0)
                return (true, $"USB готов: {deviceLines.Count} устройство(а), порт {WsServer.Port} переброшен.");
            return (false, $"adb reverse не сработал: {err}");
        }
        catch (Exception ex)
        {
            return (false, $"Ошибка adb: {ex.Message}");
        }
    }
}
