using System.Text.Json.Serialization;

namespace PhoneWheelPC;

/// <summary>Mirrors the JSON the Android app sends every ~16ms.</summary>
public class WheelState
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("seq")]
    public long Seq { get; set; }

    /// <summary>Unix milliseconds when the frame was built on the phone.
    /// Used by ConnectionManager to compute one-way latency estimate:
    /// (PC receive time) - Ts. Only meaningful when phone/PC clocks are
    /// reasonably synced (NTP), but good enough for "green/yellow/red"
    /// quality indicators even if they drift by ~50ms.</summary>
    [JsonPropertyName("ts")]
    public long Ts { get; set; }

    [JsonPropertyName("steer")]
    public double Steer { get; set; }

    [JsonPropertyName("throttle")]
    public double Throttle { get; set; }

    [JsonPropertyName("brake")]
    public double Brake { get; set; }

    [JsonPropertyName("buttons")]
    public Dictionary<string, bool> Buttons { get; set; } = new();

    [JsonPropertyName("buttonLabels")]
    public Dictionary<string, string> ButtonLabels { get; set; } = new();

    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("mode")]
    public string? Mode { get; set; }
}
