using System.Diagnostics;
using System.Runtime.InteropServices;

namespace PhoneWheelPC;

/// <summary>
/// Diagnoses the current vJoy installation and walks the user through
/// getting it working, without ever silently fetching or running a binary
/// with elevated rights behind their back. Concretely:
///
///   - If vJoy's DLL/driver isn't found, this offers to open the official
///     vJoy download page (sourceforge.net/projects/vjoy) in the user's
///     browser. The user downloads and runs the official installer
///     themselves; Windows shows its normal UAC prompt for that installer,
///     not for this app.
///   - If the user already has a vJoy installer .exe sitting in the
///     PhoneWheel folder (e.g. they place "vJoySetup.exe" next to
///     PhoneWheel.exe as a bundled convenience), this can launch it with
///     ShellExecute + Verb="runas" so Windows shows the standard UAC prompt
///     for that specific installer — administrator rights are requested by
///     Windows for that one action, not silently inherited by this app.
///   - After any install attempt, it re-runs the same diagnostic checks
///     VJoy.Initialize() already does, so success/failure is verified
///     rather than assumed.
///   - Recovery actions (reset device, re-acquire, recreate, diagnostic
///     report) all reuse the very same VJoy static class already in this
///     project — no separate code path that could drift out of sync.
/// </summary>
public static class VJoyInstaller
{
    public const string DownloadPageUrl = "https://sourceforge.net/projects/vjoy/";

    /// <summary>Name we look for if the user has chosen to bundle the
    /// official vJoy installer next to PhoneWheel.exe. We never download
    /// this ourselves — it's an optional convenience for advanced users /
    /// IT-managed deployments that place it there ahead of time.</summary>
    public const string BundledInstallerName = "vJoySetup.exe";

    public enum DiagnosisResult
    {
        Ready,              // DLL found, driver enabled, device acquirable — nothing to do
        DllMissing,         // vJoyInterface.dll not found anywhere we look
        DriverDisabled,     // DLL found but the kernel driver reports disabled
        DeviceUnavailable,  // driver enabled but device #1 is owned by another app / busy
    }

    public static DiagnosisResult Diagnose()
    {
        VJoy.LocateDll();
        if (!VJoy.DllFound) return DiagnosisResult.DllMissing;

        // Re-run Initialize()'s checks without permanently acquiring, so
        // Diagnose() is safe to call repeatedly (e.g. from a "Re-check"
        // button) without side effects if vJoy is already in use elsewhere.
        var ok = VJoy.Initialize();
        if (ok) return DiagnosisResult.Ready;

        if (VJoy.StatusMessage.Contains("не включен", StringComparison.OrdinalIgnoreCase))
            return DiagnosisResult.DriverDisabled;
        if (VJoy.StatusMessage.Contains("недоступно", StringComparison.OrdinalIgnoreCase)
            || VJoy.StatusMessage.Contains("захватить", StringComparison.OrdinalIgnoreCase))
            return DiagnosisResult.DeviceUnavailable;

        return DiagnosisResult.DllMissing;
    }

    public static bool BundledInstallerPresent() =>
        File.Exists(Path.Combine(AppContext.BaseDirectory, BundledInstallerName));

    /// <summary>Opens the official vJoy project page in the default browser
    /// so the user can download the installer themselves. We deliberately do
    /// not fetch the file ourselves — running an unsigned background
    /// download-and-launch sequence for a kernel-mode driver, without the
    /// user ever seeing where it came from, is the kind of behavior that
    /// makes antivirus (rightly) suspicious and removes the user's ability
    /// to make an informed choice about a privileged install.</summary>
    public static void OpenDownloadPage()
    {
        try
        {
            Process.Start(new ProcessStartInfo(DownloadPageUrl) { UseShellExecute = true });
        }
        catch { /* best-effort; UI shows the URL as text too */ }
    }

    /// <summary>Launches the bundled installer (if present) with a normal
    /// elevation prompt. Windows — not this app — shows the UAC dialog, and
    /// the user sees exactly which signed/unsigned executable they're
    /// elevating. Returns once the installer process exits.</summary>
    public static (bool launched, string message) RunBundledInstaller()
    {
        var path = Path.Combine(AppContext.BaseDirectory, BundledInstallerName);
        if (!File.Exists(path))
            return (false, $"{BundledInstallerName} не найден рядом с PhoneWheel.exe.");

        try
        {
            var psi = new ProcessStartInfo(path)
            {
                UseShellExecute = true,
                Verb = "runas", // Windows shows the standard UAC prompt here
            };
            using var p = Process.Start(psi);
            p?.WaitForExit();
            if (p == null) return (false, "Не удалось запустить установщик.");
            return (true, $"Установщик завершился с кодом {p.ExitCode}.");
        }
        catch (System.ComponentModel.Win32Exception)
        {
            // ERROR_CANCELLED — user clicked "No" on the UAC prompt.
            return (false, "Установка отменена пользователем (UAC).");
        }
        catch (Exception ex)
        {
            return (false, $"Ошибка запуска установщика: {ex.Message}");
        }
    }

    /// <summary>Re-acquires/resets the vJoy device without a full
    /// application restart — useful if another application released or
    /// corrupted the device handle.</summary>
    public static (bool ok, string message) RecreateDevice()
    {
        VJoy.Shutdown();
        var ok = VJoy.Initialize();
        return (ok, VJoy.StatusMessage);
    }

    /// <summary>Builds a plain-text diagnostic report covering everything
    /// VJoy.cs currently knows, suitable for pasting into a bug report or
    /// support request.</summary>
    public static string BuildDiagnosticReport()
    {
        var sb = new System.Text.StringBuilder();
        sb.AppendLine("PhoneWheel — диагностика vJoy");
        sb.AppendLine($"Время: {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
        sb.AppendLine($"ОС: {RuntimeInformation.OSDescription}");
        sb.AppendLine($"Архитектура процесса: {RuntimeInformation.ProcessArchitecture}");
        sb.AppendLine();
        sb.AppendLine($"DLL найдена:        {VJoy.DllFound}");
        sb.AppendLine($"Драйвер включен:    {VJoy.DriverEnabled}");
        sb.AppendLine($"Устройство занято:  {VJoy.Acquired}");
        sb.AppendLine($"Статус:             {VJoy.StatusMessage}");
        sb.AppendLine();
        sb.AppendLine($"Путь приложения:    {AppContext.BaseDirectory}");
        sb.AppendLine($"Установщик рядом:   {(BundledInstallerPresent() ? "найден" : "не найден")} ({BundledInstallerName})");

        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "vJoyInterface.dll"),
            @"C:\Program Files\vJoy\x64\vJoyInterface.dll",
            @"C:\Program Files\vJoy\vJoyInterface.dll",
            @"C:\Program Files (x86)\vJoy\x64\vJoyInterface.dll",
            @"C:\Program Files (x86)\vJoy\vJoyInterface.dll",
        };
        sb.AppendLine();
        sb.AppendLine("Проверенные пути к vJoyInterface.dll:");
        foreach (var c in candidates)
            sb.AppendLine($"  [{(File.Exists(c) ? "найден" : "нет")}] {c}");

        return sb.ToString();
    }
}
