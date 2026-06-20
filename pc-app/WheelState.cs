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

    /// <summary>buttonId -> pressed right now (e.g. "btn_1" -> true).</summary>
    [JsonPropertyName("buttons")]
    public Dictionary<string, bool> Buttons { get; set; } = new();

    /// <summary>buttonId -> human label the user typed on the phone
    /// (e.g. "btn_1" -> "Ручник"). Sent alongside Buttons every frame so the
    /// PC side always knows what each button is currently called, even if
    /// it was renamed after the mapping table was built.</summary>
    [JsonPropertyName("buttonLabels")]
    public Dictionary<string, string> ButtonLabels { get; set; } = new();

    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("mode")]
    public string? Mode { get; set; }
}
