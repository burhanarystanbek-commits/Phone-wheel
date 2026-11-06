using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text.Json;

namespace PhoneWheelPC;

/// <summary>
/// Self-update from GitHub Releases: finds the newest "pc-v*" release and,
/// if it is newer than this exe, downloads it and swaps the file via a tiny
/// batch script (a running exe can't overwrite itself). mapping.json lives
/// next to the exe, so settings survive the update.
/// </summary>
public static class Updater
{
    private const string Repo = "burhanarystanbek-commits/Phone-wheel";

    public static Version CurrentVersion =>
        typeof(Updater).Assembly.GetName().Version ?? new Version(0, 0, 0);

    public static async Task CheckAsync(Form owner, Action<string> log)
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
            http.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("PhoneWheelPC", CurrentVersion.ToString()));
            var json = await http.GetStringAsync($"https://api.github.com/repos/{Repo}/releases?per_page=30");
            using var doc = JsonDocument.Parse(json);

            Version? best = null; string? url = null;
            foreach (var rel in doc.RootElement.EnumerateArray())
            {
                var tag = rel.GetProperty("tag_name").GetString() ?? "";
                if (!tag.StartsWith("pc-v") || !Version.TryParse(tag[4..], out var v)) continue;
                if (best != null && v <= best) continue;
                foreach (var a in rel.GetProperty("assets").EnumerateArray())
                    if (a.GetProperty("name").GetString() == "PhoneWheelPC.exe")
                    { best = v; url = a.GetProperty("browser_download_url").GetString(); }
            }
            if (best == null || url == null || best <= CurrentVersion) return;

            var answer = (DialogResult)owner.Invoke(() => MessageBox.Show(owner,
                $"Доступна новая версия {best.ToString(3)} (у тебя {CurrentVersion.ToString(3)}).\nОбновить сейчас?",
                "Обновление PhoneWheel", MessageBoxButtons.YesNo, MessageBoxIcon.Information));
            if (answer != DialogResult.Yes) return;

            log($"Скачиваю обновление {best.ToString(3)}...");
            var exe = Environment.ProcessPath!;
            var tmp = exe + ".new";
            await File.WriteAllBytesAsync(tmp, await http.GetByteArrayAsync(url));

            var pid = Environment.ProcessId;
            var bat = Path.Combine(Path.GetTempPath(), "phonewheel_update.bat");
            File.WriteAllText(bat,
                "@echo off\r\n" +
                ":wait\r\n" +
                $"tasklist /FI \"PID eq {pid}\" | find \"{pid}\" >nul && (timeout /t 1 >nul & goto wait)\r\n" +
                $"move /Y \"{tmp}\" \"{exe}\" >nul\r\n" +
                $"start \"\" \"{exe}\"\r\n" +
                "del \"%~f0\"\r\n");
            Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{bat}\"")
                { CreateNoWindow = true, UseShellExecute = false });
            owner.Invoke(() => Application.Exit());
        }
        catch (Exception ex)
        {
            log($"Проверка обновлений не удалась: {ex.Message}");
        }
    }
}
