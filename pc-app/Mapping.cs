using System.Text.Json;

namespace PhoneWheelPC;

/// <summary>
/// Maps the action ids the phone sends (gear_up, gear_down, handbrake, drs,
/// pit_limiter) to a vJoy button number. This is the ONLY place the phone's
/// "preset" choice actually affects the PC side — the phone just decides
/// which buttons are drawn on screen, the PC decides what they do in Windows.
/// Once vJoy button N is set here, you still bind "vJoy Button N" inside
/// each game's own Controls menu — that one-time step happens in-game.
/// </summary>
public class Mapping
{
    public Dictionary<string, int> ActionToButton { get; set; } = new();

    public static readonly string[] KnownActions =
        { "gear_up", "gear_down", "handbrake", "drs", "pit_limiter" };

    public static Mapping Default(string presetKey) => presetKey switch
    {
        "f1" => new Mapping
        {
            ActionToButton = new()
            {
                ["gear_up"] = 1,
                ["gear_down"] = 2,
                ["drs"] = 3,
                ["pit_limiter"] = 4,
                ["handbrake"] = 5,
            }
        },
        "iracing" => new Mapping
        {
            ActionToButton = new()
            {
                ["gear_up"] = 1,
                ["gear_down"] = 2,
                ["handbrake"] = 3,
                ["pit_limiter"] = 4,
                ["drs"] = 5,
            }
        },
        _ => new Mapping // assetto_corsa and fallback
        {
            ActionToButton = new()
            {
                ["gear_up"] = 1,
                ["gear_down"] = 2,
                ["handbrake"] = 3,
                ["pit_limiter"] = 4,
                ["drs"] = 5,
            }
        }
    };

    private static string ConfigPath(string presetKey) =>
        Path.Combine(AppContext.BaseDirectory, $"mapping.{presetKey}.json");

    public static Mapping LoadOrDefault(string presetKey)
    {
        var path = ConfigPath(presetKey);
        try
        {
            if (File.Exists(path))
            {
                var json = File.ReadAllText(path);
                var loaded = JsonSerializer.Deserialize<Mapping>(json);
                if (loaded != null) return loaded;
            }
        }
        catch { /* fall back to defaults below */ }
        return Default(presetKey);
    }

    public void Save(string presetKey)
    {
        var path = ConfigPath(presetKey);
        var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(path, json);
    }

    /// <summary>Short, human-readable per-game hint for where to bind these in-game.</summary>
    public static string GameHint(string presetKey) => presetKey switch
    {
        "f1" => "Игра → Settings → Controls → выбери схему 'Wheel' → назначь vJoy Button N на Gear Up/Down, DRS, Pit Limiter, Handbrake.",
        "iracing" => "Игра → Options → Controls → Calibrate/Buttons → назначь vJoy Button N (телефон шлёт как нажатие кнопки).",
        _ => "Игра → Options → Controls → Steering Wheel → Buttons → назначь vJoy Button N на нужное действие.",
    };
}
