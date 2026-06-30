using System.Runtime.InteropServices;

namespace PhoneWheelPC;

/// <summary>
/// Thin wrapper around the official vJoy SDK's native vJoyInterface.dll.
/// vJoy itself (the kernel driver) must be installed separately by the user —
/// see https://sourceforge.net/projects/vjoy/ — this class only talks to the
/// DLL that the vJoy installer places on the system.
/// </summary>
public static class VJoy
{
    private const uint DeviceId = 1; // vJoy device #1, configured via "Configure vJoy"

    // vJoy HID usage axis ids (from vJoy SDK's public.h)
    public const int HID_USAGE_X  = 0x30;
    public const int HID_USAGE_Y  = 0x31;
    public const int HID_USAGE_Z  = 0x32;
    public const int HID_USAGE_RX = 0x33;
    public const int HID_USAGE_RY = 0x34;
    public const int HID_USAGE_RZ = 0x35;
    public const int HID_USAGE_SLIDER  = 0x36;
    public const int HID_USAGE_DIAL    = 0x37;

    /// <summary>Extra rotary-knob axes (ABS bias, traction control, brake
    /// balance, etc) map onto these five vJoy axes — everything besides the
    /// three already used for steer/throttle/brake. Order here is the order
    /// knobs get auto-assigned in, matching ExtraAxisLabels below.</summary>
    public static readonly int[] ExtraAxisIds =
        { HID_USAGE_RX, HID_USAGE_RY, HID_USAGE_RZ, HID_USAGE_SLIDER, HID_USAGE_DIAL };

    public static readonly string[] ExtraAxisLabels =
        { "Rx", "Ry", "Rz", "Slider", "Dial" };

    public const int AxisMin = 0;
    public const int AxisMax = 32767;
    public const int AxisCenter = 16383;

    [DllImport("vJoyInterface.dll")] private static extern bool vJoyEnabled();
    [DllImport("vJoyInterface.dll")] private static extern int GetVJDStatus(uint rID);
    [DllImport("vJoyInterface.dll")] private static extern bool AcquireVJD(uint rID);
    [DllImport("vJoyInterface.dll")] private static extern void RelinquishVJD(uint rID);
    [DllImport("vJoyInterface.dll")] private static extern bool ResetVJD(uint rID);
    [DllImport("vJoyInterface.dll")] private static extern bool SetAxis(int value, uint rID, int axis);
    [DllImport("vJoyInterface.dll")] private static extern bool SetBtn(bool value, uint rID, byte btnNumber);
    [DllImport("vJoyInterface.dll")] private static extern short GetVJDButtonNumber(uint rID);

    // VJD_STAT enum values from the SDK
    private const int VJD_STAT_OWN  = 0;
    private const int VJD_STAT_FREE = 1;

    public static bool DllFound { get; private set; }
    public static bool DriverEnabled { get; private set; }
    public static bool Acquired { get; private set; }
    public static string StatusMessage { get; private set; } = "Не инициализировано";

    /// <summary>
    /// Looks for vJoyInterface.dll in the usual install locations and copies
    /// it next to our own exe so the P/Invoke loader (which only searches the
    /// app folder, System32 and PATH) can find it without asking the user to
    /// edit their PATH.
    /// </summary>
    public static void LocateDll()
    {
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "vJoyInterface.dll"),
            @"C:\Program Files\vJoy\x64\vJoyInterface.dll",
            @"C:\Program Files\vJoy\vJoyInterface.dll",
            @"C:\Program Files (x86)\vJoy\x64\vJoyInterface.dll",
            @"C:\Program Files (x86)\vJoy\vJoyInterface.dll",
        };

        var target = Path.Combine(AppContext.BaseDirectory, "vJoyInterface.dll");
        if (File.Exists(target)) { DllFound = true; return; }

        foreach (var c in candidates)
        {
            if (File.Exists(c))
            {
                try
                {
                    if (!string.Equals(c, target, StringComparison.OrdinalIgnoreCase))
                        File.Copy(c, target, overwrite: true);
                    DllFound = true;
                    return;
                }
                catch { /* fall through and try the next candidate */ }
            }
        }
        DllFound = false;
    }

    public static bool Initialize()
    {
        LocateDll();
        if (!DllFound)
        {
            StatusMessage = "vJoyInterface.dll не найдена — установите драйвер vJoy.";
            return false;
        }
        try
        {
            DriverEnabled = vJoyEnabled();
            if (!DriverEnabled)
            {
                StatusMessage = "Драйвер vJoy не включен. Откройте 'Configure vJoy' и включите устройство 1.";
                return false;
            }
            var status = GetVJDStatus(DeviceId);
            if (status != VJD_STAT_OWN && status != VJD_STAT_FREE)
            {
                StatusMessage = $"Устройство vJoy #1 недоступно (status={status}).";
                return false;
            }
            if (status == VJD_STAT_FREE && !AcquireVJD(DeviceId))
            {
                StatusMessage = "Не удалось захватить устройство vJoy #1.";
                return false;
            }
            ResetVJD(DeviceId);
            Acquired = true;
            StatusMessage = "vJoy подключен (устройство #1).";
            return true;
        }
        catch (DllNotFoundException)
        {
            DllFound = false;
            StatusMessage = "Не удалось загрузить vJoyInterface.dll.";
            return false;
        }
        catch (Exception ex)
        {
            StatusMessage = $"Ошибка vJoy: {ex.Message}";
            return false;
        }
    }

    public static void Shutdown()
    {
        if (Acquired) RelinquishVJD(DeviceId);
        Acquired = false;
    }

    public static void SetSteer(double minusOneToOne)
    {
        if (!Acquired) return;
        var v = AxisCenter + (int)(minusOneToOne * AxisCenter);
        SetAxis(Math.Clamp(v, AxisMin, AxisMax), DeviceId, HID_USAGE_X);
    }

    public static void SetThrottle(double zeroToOne)
    {
        if (!Acquired) return;
        SetAxis(Math.Clamp((int)(zeroToOne * AxisMax), AxisMin, AxisMax), DeviceId, HID_USAGE_Y);
    }

    public static void SetBrake(double zeroToOne)
    {
        if (!Acquired) return;
        SetAxis(Math.Clamp((int)(zeroToOne * AxisMax), AxisMin, AxisMax), DeviceId, HID_USAGE_Z);
    }

    public static void SetButton(int buttonNumber, bool pressed)
    {
        if (!Acquired || buttonNumber <= 0) return;
        SetBtn(pressed, DeviceId, (byte)buttonNumber);
    }

    /// <summary>Sets one of the extra rotary-knob axes (index 0..4, see
    /// ExtraAxisIds) to a value in 0..1. Used for user-defined knobs like
    /// ABS bias, traction control level, or brake balance — anything that
    /// isn't steer/throttle/brake but still needs to be a continuous axis
    /// rather than a button.</summary>
    public static void SetExtraAxis(int axisIndex, double zeroToOne)
    {
        if (!Acquired) return;
        if (axisIndex < 0 || axisIndex >= ExtraAxisIds.Length) return;
        var v = Math.Clamp((int)(zeroToOne * AxisMax), AxisMin, AxisMax);
        SetAxis(v, DeviceId, ExtraAxisIds[axisIndex]);
    }
}
