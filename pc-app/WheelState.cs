using System.Text.Json.Serialization;

namespace PhoneWheelPC;

/// <summary>Mirrors the JSON the Android app sends every ~16ms over the WebSocket.</summary>
public class WheelState
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("seq")]
    public long Seq { get; set; }

    [JsonPropertyName("steer")]
    public double Steer { get; set; }

    [JsonPropertyName("throttle")]
    public double Throttle { get; set; }

    [JsonPropertyName("brake")]
    public double Brake { get; set; }

    [JsonPropertyName("buttons")]
    public Dictionary<string, bool> Buttons { get; set; } = new();

    [JsonPropertyName("preset")]
    public string Preset { get; set; } = "";

    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("mode")]
    public string? Mode { get; set; }
}
