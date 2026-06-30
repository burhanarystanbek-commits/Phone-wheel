using System.Text.Json;

namespace PhoneWheelPC;

/// <summary>
/// Maps whatever button ids the phone is currently sending ("btn_1", "btn_2",
/// ...) to a vJoy button number, plus a friendly display name for the PC UI.
/// The phone decides how many buttons exist and what they're called there;
/// the PC decides which vJoy button number each one fires. Once vJoy button
/// N is set here, you still bind "vJoy Button N" inside each game's own
/// Controls menu — that one-time step happens in-game.
/// </summary>
public class ButtonMapEntry
{
    public int VjoyButton { get; set; } = 1;

    /// <summary>Friendly name shown in the PC UI for this entry. Defaults to
    /// whatever label the phone is currently sending for this id, but can be
    /// overridden here independent of the phone-side label.</summary>
    public string DisplayName { get; set; } = "";
}

/// <summary>
/// Maps a rotary-knob id from the phone ("axis_1", "axis_2", ...) to one of
/// vJoy's 5 extra continuous axes (Rx, Ry, Rz, Slider, Dial — see
/// VJoy.ExtraAxisIds), plus a friendly display name. Same auto-discovery
/// pattern as ButtonMapEntry: the phone decides how many knobs exist, the
/// PC decides which vJoy axis each one drives.
/// </summary>
public class AxisMapEntry
{
    /// <summary>Index into VJoy.ExtraAxisIds (0..4), not the raw HID usage
    /// id — keeps the PC UI's dropdown simple ("Rx", "Ry", ... by name).</summary>
    public int VjoyAxisIndex { get; set; } = 0;

    public string DisplayName { get; set; } = "";
}

public class Mapping
{
    /// <summary>buttonId -> mapping entry. Grows automatically as new
    /// buttons show up from the phone; nothing here is fixed in advance.</summary>
    public Dictionary<string, ButtonMapEntry> Entries { get; set; } = new();

    /// <summary>axisId -> mapping entry for rotary knobs (ABS, TC, brake
    /// balance, etc). Same auto-discovery growth pattern as Entries.</summary>
    public Dictionary<string, AxisMapEntry> AxisEntries { get; set; } = new();

    private static string ConfigPath() =>
        Path.Combine(AppContext.BaseDirectory, "mapping.json");

    public static Mapping LoadOrDefault()
    {
        var path = ConfigPath();
        try
        {
            if (File.Exists(path))
            {
                var json = File.ReadAllText(path);
                var loaded = JsonSerializer.Deserialize<Mapping>(json);
                if (loaded != null) return loaded;
            }
        }
        catch { /* fall back to empty mapping below */ }
        return new Mapping();
    }

    public void Save()
    {
        var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(ConfigPath(), json);
    }

    /// <summary>Ensures every button id currently seen from the phone has a
    /// mapping row, auto-assigning the next free vJoy button number to any
    /// new id. Returns true if the mapping changed (so the caller can
    /// refresh the UI / persist it).</summary>
    public bool EnsureEntriesFor(IEnumerable<string> buttonIds, IDictionary<string, string> labels)
    {
        var changed = false;
        foreach (var id in buttonIds)
        {
            if (!Entries.ContainsKey(id))
            {
                var nextBtn = Entries.Count == 0 ? 1 : Entries.Values.Max(e => e.VjoyButton) + 1;
                if (nextBtn > 32) nextBtn = 32;
                Entries[id] = new ButtonMapEntry
                {
                    VjoyButton = nextBtn,
                    DisplayName = labels.TryGetValue(id, out var lbl) ? lbl : id
                };
                changed = true;
            }
            else if (string.IsNullOrEmpty(Entries[id].DisplayName) && labels.TryGetValue(id, out var lbl2))
            {
                Entries[id].DisplayName = lbl2;
                changed = true;
            }
        }
        return changed;
    }

    public const string GameHint =
        "Игра → Options/Settings → Controls → Steering Wheel → Buttons → назначь vJoy Button N на нужное действие.";

    /// <summary>Same auto-discovery growth pattern as EnsureEntriesFor, but
    /// for rotary-knob axes. Auto-assigns the next free vJoy extra-axis slot
    /// (0..4) to any newly-seen axis id, wrapping back to 0 if all 5 are
    /// already taken (rare — 5 knobs is already a lot).</summary>
    public bool EnsureAxisEntriesFor(IEnumerable<string> axisIds, IDictionary<string, string> labels)
    {
        var changed = false;
        foreach (var id in axisIds)
        {
            if (!AxisEntries.ContainsKey(id))
            {
                var used = AxisEntries.Values.Select(e => e.VjoyAxisIndex).ToHashSet();
                var nextIdx = 0;
                while (used.Contains(nextIdx) && nextIdx < VJoy.ExtraAxisIds.Length - 1) nextIdx++;
                AxisEntries[id] = new AxisMapEntry
                {
                    VjoyAxisIndex = nextIdx,
                    DisplayName = labels.TryGetValue(id, out var lbl) ? lbl : id
                };
                changed = true;
            }
            else if (string.IsNullOrEmpty(AxisEntries[id].DisplayName) && labels.TryGetValue(id, out var lbl2))
            {
                AxisEntries[id].DisplayName = lbl2;
                changed = true;
            }
        }
        return changed;
    }
}
