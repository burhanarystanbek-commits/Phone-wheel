using System.Text.Json;
using PhoneWheelPC.Transport;

namespace PhoneWheelPC;

public class MainForm : Form
{
    private readonly ConnectionManager _connectionManager = new();
    private Mapping _mapping = Mapping.LoadOrDefault();
    private ComboBox _modeSelector = new();
    private Label _connectionKindLabel = new();

    private Label _vjoyStatus = new();
    private Label _serverStatus = new();
    private Label _phoneStatus = new();
    private Label _liveValues = new();
    private TextBox _logBox = new();
    private FlowLayoutPanel _mappingPanel = new();
    private readonly Dictionary<string, NumericUpDown> _buttonInputs = new();
    private readonly Dictionary<string, TextBox> _nameInputs = new();
    private readonly Dictionary<string, Label> _pressedDots = new();
    private Button _fixAclBtn = new();
    private Label _emptyHint = new();
    private Button _vjoySetupBtn = new();
    private Button _vjoyToolsBtn = new();

    // Dashboard labels
    private Label _dashMode       = new();
    private Label _dashPeer       = new();
    private Label _dashPing       = new();
    private Label _dashRate       = new();
    private Label _dashUptime     = new();
    private Label _dashSteer      = new();
    private Label _dashThrottle   = new();
    private Label _dashBrake      = new();
    private Label _dashVjoyState  = new();
    private Panel _pingDot        = new();
    private System.Windows.Forms.Timer _dashTimer = new();

    public MainForm()
    {
        Text = "PhoneWheel — PC App";
        Width = 820;
        Height = 780;
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 9f);

        BuildUi();

        Load += (_, _) => StartUp();
        FormClosing += (_, _) => { _connectionManager.Dispose(); VJoy.Shutdown(); };
    }

    private void BuildUi()
    {
        var pad = 12;
        var y = pad;

        var title = new Label { Text = "PhoneWheel", Font = new Font("Segoe UI", 16f, FontStyle.Bold), AutoSize = true, Left = pad, Top = y };
        Controls.Add(title);
        y += 34;

        _vjoyStatus = new Label { Left = pad, Top = y, Width = 360, Height = 20, Text = "vJoy: …" };
        Controls.Add(_vjoyStatus);

        _vjoySetupBtn = new Button { Left = 370, Top = y - 2, Width = 110, Height = 24, Text = "Настроить vJoy", Visible = false };
        _vjoySetupBtn.Click += (_, _) => ShowVJoySetupDialog();
        Controls.Add(_vjoySetupBtn);

        _vjoyToolsBtn = new Button { Left = 484, Top = y - 2, Width = 126, Height = 24, Text = "Инструменты vJoy" };
        _vjoyToolsBtn.Click += (_, _) => ShowVJoyToolsDialog();
        Controls.Add(_vjoyToolsBtn);
        y += 22;

        Controls.Add(new Label { Left = pad, Top = y + 3, Width = 60, Text = "Режим:" });
        _modeSelector = new ComboBox { Left = pad + 60, Top = y, Width = 140, DropDownStyle = ComboBoxStyle.DropDownList };
        _modeSelector.Items.AddRange(new object[] { "Wi-Fi", "USB", "Bluetooth" });
        _modeSelector.SelectedIndex = 0;
        _modeSelector.SelectedIndexChanged += (_, _) => SwitchMode();
        Controls.Add(_modeSelector);

        _connectionKindLabel = new Label { Left = pad + 210, Top = y + 3, Width = 380, Text = "" };
        Controls.Add(_connectionKindLabel);
        y += 26;

        _serverStatus = new Label { Left = pad, Top = y, Width = 780, Height = 20, Text = "Сервер: …" };
        Controls.Add(_serverStatus);

        _fixAclBtn = new Button { Left = 500, Top = y - 2, Width = 110, Height = 24, Text = "Настроить доступ", Visible = false };
        _fixAclBtn.Click += (_, _) =>
        {
            if (WebSocketTransport.TryFixUrlAcl())
            {
                AppendLog("Права настроены, перезапускаю сервер...");
                StartServer();
            }
            else
            {
                AppendLog("Не удалось настроить права. Запустите приложение от имени администратора.");
            }
        };
        Controls.Add(_fixAclBtn);
        y += 24;

        var usbBtn = new Button { Left = pad, Top = y, Width = 200, Height = 26, Text = "Настроить USB (adb reverse)" };
        usbBtn.Click += (_, _) =>
        {
            var (ok, message) = AdbHelper.SetupReverse();
            AppendLog(message);
            if (ok && _modeSelector.SelectedItem as string != "USB")
            {
                _modeSelector.SelectedItem = "USB"; // triggers SwitchMode via event
            }
        };
        Controls.Add(usbBtn);
        y += 32;

        _phoneStatus = new Label { Left = pad, Top = y, Width = 780, Height = 20, Text = "Телефон: не подключен", ForeColor = Color.DarkOrange };
        Controls.Add(_phoneStatus);
        y += 22;

        _liveValues = new Label { Left = pad, Top = y, Width = 780, Height = 18, Text = "" };
        Controls.Add(_liveValues);
        y += 22;

        // ─── Dashboard panel ───────────────────────────────────────────
        var dash = new Panel
        {
            Left = pad, Top = y, Width = 790, Height = 110,
            BorderStyle = BorderStyle.FixedSingle,
            BackColor = Color.FromArgb(245, 245, 245),
        };
        Controls.Add(dash);

        // Helper: column-based label pair
        int col1 = 8, col2 = 150, col3 = 300, col4 = 450, col5 = 600;
        int row1 = 6, row2 = 30, row3 = 54, row4 = 78;

        Label DL(string text, int x, int y2, bool bold = false, Color? col = null) {
            var l = new Label {
                Left = x, Top = y2, AutoSize = true,
                Text = text,
                ForeColor = col ?? Color.FromArgb(80, 80, 80),
                Font = bold ? new Font(Font, FontStyle.Bold) : Font,
            };
            dash.Controls.Add(l);
            return l;
        }

        DL("ПОДКЛЮЧЕНИЕ", col1, row1, bold: true);
        DL("Режим:",   col1, row2); _dashMode    = DL("—", col1+64, row2, col: Color.DimGray);
        DL("Устройство:", col1, row3); _dashPeer = DL("—", col1+78, row3, col: Color.DimGray);
        DL("Время:",   col1, row4); _dashUptime  = DL("—", col1+44, row4, col: Color.DimGray);

        DL("ПИНГ / ПАКЕТЫ", col3, row1, bold: true);
        DL("Пинг:",    col3, row2);
        _pingDot = new Panel { Left = col3+44, Top = row2+2, Width = 12, Height = 12, BackColor = Color.LightGray };
        dash.Controls.Add(_pingDot);
        _dashPing  = DL("—", col3+62, row2, col: Color.DimGray);
        DL("Частота:", col3, row3); _dashRate    = DL("—", col3+56, row3, col: Color.DimGray);

        DL("КОНТРОЛЛЕР", col2, row1, bold: true);
        DL("Руль:",     col2, row2); _dashSteer    = DL("—", col2+40, row2, col: Color.DimGray);
        DL("Газ:",      col2, row3); _dashThrottle = DL("—", col2+28, row3, col: Color.DimGray);
        DL("Тормоз:",   col2, row4); _dashBrake    = DL("—", col2+56, row4, col: Color.DimGray);

        DL("vJOY", col4, row1, bold: true);
        _dashVjoyState = DL("—", col4, row2, col: Color.DimGray);

        y += 118;

        var hint = new Label { Left = pad, Top = y, Width = 780, Height = 20, ForeColor = Color.Gray, Text = Mapping.GameHint };
        Controls.Add(hint);
        y += 26;

        Controls.Add(new Label
        {
            Left = pad, Top = y, Width = 780,
            Text = "Кнопки телефона → номер кнопки vJoy и название (привяжи номер в настройках управления игры):",
            Font = new Font(Font, FontStyle.Bold)
        });
        y += 22;

        _emptyHint = new Label
        {
            Left = pad, Top = y, Width = 780, Height = 40,
            ForeColor = Color.Gray,
            Text = "Кнопки появятся здесь автоматически, как только добавишь их на телефоне\n(значок ⚙ → + Добавить кнопку)."
        };
        Controls.Add(_emptyHint);

        _mappingPanel = new FlowLayoutPanel
        {
            Left = pad, Top = y, Width = 790, Height = 200,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            BorderStyle = BorderStyle.FixedSingle,
        };
        Controls.Add(_mappingPanel);
        y += 230;

        var saveBtn = new Button { Left = pad, Top = y, Width = 160, Height = 30, Text = "Сохранить маппинг" };
        saveBtn.Click += (_, _) => SaveMapping();
        Controls.Add(saveBtn);

        var clearBtn = new Button { Left = pad + 170, Top = y, Width = 160, Height = 30, Text = "Очистить список" };
        clearBtn.Click += (_, _) =>
        {
            _mapping = new Mapping();
            RefreshMappingPanel();
            AppendLog("Список кнопок очищен. Появятся снова при следующем нажатии на телефоне.");
        };
        Controls.Add(clearBtn);
        y += 40;

        Controls.Add(new Label { Left = pad, Top = y, Width = 780, Text = "Журнал:", Font = new Font(Font, FontStyle.Bold) });
        y += 20;

        _logBox = new TextBox { Left = pad, Top = y, Width = 790, Height = 70, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical };
        Controls.Add(_logBox);

        RefreshMappingPanel();
    }

    /// <summary>Rebuilds one row per known button id: [pressed dot] [name on phone] [display name input] [vJoy # input].</summary>
    private void RefreshMappingPanel()
    {
        _mappingPanel.SuspendLayout();
        _mappingPanel.Controls.Clear();
        _buttonInputs.Clear();
        _nameInputs.Clear();
        _pressedDots.Clear();

        var ids = _mapping.Entries.Keys.OrderBy(id => _mapping.Entries[id].VjoyButton).ToList();
        _emptyHint.Visible = ids.Count == 0;
        _mappingPanel.Visible = ids.Count > 0;

        foreach (var id in ids)
        {
            var entry = _mapping.Entries[id];
            var row = new Panel { Width = 760, Height = 32, Margin = new Padding(2) };

            var dot = new Label
            {
                Left = 0, Top = 6, Width = 14, Height = 14,
                Text = "●", ForeColor = Color.DimGray, Font = new Font("Segoe UI", 10f)
            };
            row.Controls.Add(dot);
            _pressedDots[id] = dot;

            var idLabel = new Label { Left = 20, Top = 7, Width = 70, Text = id, ForeColor = Color.Gray };
            row.Controls.Add(idLabel);

            var nameInput = new TextBox { Left = 95, Top = 4, Width = 280, Text = entry.DisplayName };
            row.Controls.Add(nameInput);
            _nameInputs[id] = nameInput;

            var vjoyLabel = new Label { Left = 390, Top = 7, Width = 70, Text = "vJoy #" };
            row.Controls.Add(vjoyLabel);

            var numInput = new NumericUpDown { Left = 460, Top = 4, Width = 60, Minimum = 1, Maximum = 32, Value = Math.Clamp(entry.VjoyButton, 1, 32) };
            row.Controls.Add(numInput);
            _buttonInputs[id] = numInput;

            _mappingPanel.Controls.Add(row);
        }

        _mappingPanel.ResumeLayout();
    }

    private void StartUp()
    {
        RefreshVJoyStatus();

        _connectionManager.StateReceived += OnState;
        _connectionManager.StateChanged += s => UiThread(() =>
        {
            OnConnectionStateChanged(s);
            UpdateDashboardConnection();
        });
        _connectionManager.Log += msg => UiThread(() => AppendLog(msg));
        _connectionManager.PacketRateChanged += hz => UiThread(() =>
        {
            _dashRate.Text = $"{hz} Гц";
        });
        _connectionManager.LatencyChanged += ms => UiThread(() =>
        {
            UpdatePingDot(ms);
        });

        // Dashboard refresh timer — uptime ticks every second.
        _dashTimer.Interval = 1000;
        _dashTimer.Tick += (_, _) => UiThread(UpdateDashboardConnection);
        _dashTimer.Start();

        StartServer();
    }

    /// <summary>Re-runs vJoy diagnosis and updates the status label + the
    /// visibility of the "Настроить vJoy" button. Called on startup and
    /// again after any setup/recovery action so the UI always reflects
    /// what's actually true, not what we assumed would happen.</summary>
    private void RefreshVJoyStatus()
    {
        var diagnosis = VJoyInstaller.Diagnose();
        _vjoyStatus.Text = $"vJoy: {VJoy.StatusMessage}";
        _vjoyStatus.ForeColor = diagnosis == VJoyInstaller.DiagnosisResult.Ready ? Color.SeaGreen : Color.Firebrick;
        _vjoySetupBtn.Visible = diagnosis != VJoyInstaller.DiagnosisResult.Ready;
        _dashVjoyState.Text = diagnosis == VJoyInstaller.DiagnosisResult.Ready
            ? "Готов ✓" : VJoy.StatusMessage;
        _dashVjoyState.ForeColor = diagnosis == VJoyInstaller.DiagnosisResult.Ready
            ? Color.SeaGreen : Color.Firebrick;
    }

    private void ShowVJoySetupDialog()
    {
        var diagnosis = VJoyInstaller.Diagnose();
        var bundled = VJoyInstaller.BundledInstallerPresent();

        var message = diagnosis switch
        {
            VJoyInstaller.DiagnosisResult.DllMissing =>
                "vJoy не установлен на этом компьютере.\n\nvJoy — виртуальный джойстик-драйвер, через который PhoneWheel передаёт руль/педали/кнопки в игры.",
            VJoyInstaller.DiagnosisResult.DriverDisabled =>
                "vJoy установлен, но устройство #1 выключено.\nОткрой 'Configure vJoy' (через меню Пуск) и включи устройство 1 с осями X, Y, Z и хотя бы 8 кнопками.",
            VJoyInstaller.DiagnosisResult.DeviceUnavailable =>
                "Устройство vJoy #1 занято другой программой или недоступно.\nЗакрой другие программы, использующие vJoy, и попробуй снова.",
            _ => "vJoy готов к работе.",
        };

        var options = bundled
            ? "Рядом с PhoneWheel.exe найден установщик vJoy — можно запустить его прямо сейчас (потребуется подтверждение Windows)."
            : $"Скачай официальный установщик vJoy с {VJoyInstaller.DownloadPageUrl} и запусти его, затем нажми 'Проверить снова'.";

        using var dlg = new Form
        {
            Text = "Настройка vJoy",
            Width = 460,
            Height = bundled ? 260 : 230,
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
        };

        var lbl = new Label { Left = 16, Top = 16, Width = 420, Height = 80, Text = message };
        dlg.Controls.Add(lbl);
        var lbl2 = new Label { Left = 16, Top = 100, Width = 420, Height = 50, Text = options, ForeColor = Color.DimGray };
        dlg.Controls.Add(lbl2);

        var openBtn = new Button { Left = 16, Top = 160, Width = 200, Height = 30, Text = "Открыть страницу загрузки" };
        openBtn.Click += (_, _) => VJoyInstaller.OpenDownloadPage();
        dlg.Controls.Add(openBtn);

        if (bundled)
        {
            var runBtn = new Button { Left = 226, Top = 160, Width = 200, Height = 30, Text = "Запустить установщик" };
            runBtn.Click += (_, _) =>
            {
                var (launched, msg) = VJoyInstaller.RunBundledInstaller();
                AppendLog(msg);
                RefreshVJoyStatus();
                if (VJoyInstaller.Diagnose() == VJoyInstaller.DiagnosisResult.Ready)
                {
                    MessageBox.Show(dlg, "vJoy успешно настроен.", "Готово", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    dlg.Close();
                }
            };
            dlg.Controls.Add(runBtn);
        }

        var recheckBtn = new Button { Left = 16, Top = 196, Width = 200, Height = 30, Text = "Проверить снова" };
        recheckBtn.Click += (_, _) =>
        {
            RefreshVJoyStatus();
            if (VJoyInstaller.Diagnose() == VJoyInstaller.DiagnosisResult.Ready)
            {
                MessageBox.Show(dlg, "vJoy готов к работе!", "Готово", MessageBoxButtons.OK, MessageBoxIcon.Information);
                dlg.Close();
            }
            else
            {
                MessageBox.Show(dlg, VJoy.StatusMessage, "Ещё не готово", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        };
        dlg.Controls.Add(recheckBtn);

        dlg.ShowDialog(this);
    }

    private void ShowVJoyToolsDialog()
    {
        using var dlg = new Form
        {
            Text = "Инструменты vJoy",
            Width = 520,
            Height = 420,
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
        };

        var reportBox = new TextBox
        {
            Left = 12, Top = 12, Width = 780, Height = 280,
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Font = new Font("Consolas", 8.5f),
            Text = VJoyInstaller.BuildDiagnosticReport(),
        };
        dlg.Controls.Add(reportBox);

        var resetBtn = new Button { Left = 12, Top = 304, Width = 150, Height = 30, Text = "Пересоздать устройство" };
        resetBtn.Click += (_, _) =>
        {
            var (ok, msg) = VJoyInstaller.RecreateDevice();
            AppendLog(msg);
            RefreshVJoyStatus();
            reportBox.Text = VJoyInstaller.BuildDiagnosticReport();
            MessageBox.Show(dlg, msg, ok ? "Готово" : "Не удалось", MessageBoxButtons.OK,
                ok ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
        };
        dlg.Controls.Add(resetBtn);

        var reinstallBtn = new Button { Left = 168, Top = 304, Width = 150, Height = 30, Text = "Переустановить драйвер" };
        reinstallBtn.Click += (_, _) =>
        {
            dlg.Close();
            ShowVJoySetupDialog();
        };
        dlg.Controls.Add(reinstallBtn);

        var copyBtn = new Button { Left = 324, Top = 304, Width = 150, Height = 30, Text = "Скопировать отчёт" };
        copyBtn.Click += (_, _) =>
        {
            try { Clipboard.SetText(reportBox.Text); AppendLog("Диагностический отчёт скопирован в буфер обмена."); }
            catch { /* ignore clipboard failures */ }
        };
        dlg.Controls.Add(copyBtn);

        var refreshBtn = new Button { Left = 12, Top = 344, Width = 150, Height = 30, Text = "Обновить отчёт" };
        refreshBtn.Click += (_, _) => reportBox.Text = VJoyInstaller.BuildDiagnosticReport();
        dlg.Controls.Add(refreshBtn);

        dlg.ShowDialog(this);
    }

    private void OnConnectionStateChanged(ConnectionState state)
    {
        switch (state)
        {
            case ConnectionState.Connected:
                _phoneStatus.Text = $"Телефон: подключен ({_connectionManager.PeerDescription})";
                _phoneStatus.ForeColor = Color.SeaGreen;
                break;
            case ConnectionState.Listening:
                _phoneStatus.Text = "Телефон: не подключен (ожидание)";
                _phoneStatus.ForeColor = Color.DarkOrange;
                break;
            case ConnectionState.Connecting:
                _phoneStatus.Text = "Телефон: подключение...";
                _phoneStatus.ForeColor = Color.DarkOrange;
                break;
            case ConnectionState.Reconnecting:
                _phoneStatus.Text = _connectionManager.ActiveKind == TransportKind.Bluetooth
                    ? "Телефон: отключился, жду переподключения по Bluetooth..."
                    : "Телефон: переподключение...";
                _phoneStatus.ForeColor = Color.DarkOrange;
                break;
            case ConnectionState.Error:
                _phoneStatus.Text = "Телефон: ошибка соединения";
                _phoneStatus.ForeColor = Color.Firebrick;
                break;
            default:
                _phoneStatus.Text = "Телефон: не подключен";
                _phoneStatus.ForeColor = Color.DarkOrange;
                break;
        }
    }

    /// <summary>Starts the transport matching whatever is currently selected
    /// in the mode dropdown (defaults to Wi-Fi on first launch). Both Wi-Fi
    /// and USB use the same WebSocketTransport/port — selecting between them
    /// is purely informational until Bluetooth (a genuinely different
    /// transport) is selected.</summary>
    private void StartServer()
    {
        var kind = SelectedKind();
        var started = _connectionManager.SwitchTo(kind);
        UpdateServerStatusUi(kind, started);
    }

    private void SwitchMode()
    {
        var kind = SelectedKind();
        AppendLog($"Переключение на режим: {_modeSelector.SelectedItem}");
        var started = _connectionManager.SwitchTo(kind);
        UpdateServerStatusUi(kind, started);
    }

    private TransportKind SelectedKind() => (_modeSelector.SelectedItem as string) switch
    {
        "USB" => TransportKind.Usb,
        "Bluetooth" => TransportKind.Bluetooth,
        _ => TransportKind.WiFi,
    };

    private void UpdateServerStatusUi(TransportKind kind, bool started)
    {
        if (kind == TransportKind.Bluetooth)
        {
            _serverStatus.Text = started
                ? $"Сервер: Bluetooth ожидает подключение (UUID {BluetoothServer.ServiceUuid})"
                : "Сервер: Bluetooth недоступен — проверь, что адаптер включен в Windows";
            _serverStatus.ForeColor = started ? Color.SeaGreen : Color.Firebrick;
            _fixAclBtn.Visible = false;
            _connectionKindLabel.Text = started ? "" : "Включи Bluetooth в Windows и переключи режим ещё раз.";
            return;
        }

        if (started)
        {
            _serverStatus.Text = $"Сервер: слушает порт {WsServer.Port}"
                + (kind == TransportKind.Usb ? " (через adb reverse)" : " (Wi-Fi, LAN)");
            _serverStatus.ForeColor = Color.SeaGreen;
            _fixAclBtn.Visible = false;
        }
        else
        {
            _serverStatus.Text = "Сервер: нет доступа к порту — нажми 'Настроить доступ' (один раз) или запусти от имени администратора";
            _serverStatus.ForeColor = Color.Firebrick;
            _fixAclBtn.Visible = true;
        }
    }

    private void SaveMapping()
    {
        foreach (var id in _buttonInputs.Keys)
        {
            if (!_mapping.Entries.TryGetValue(id, out var entry)) continue;
            entry.VjoyButton = (int)_buttonInputs[id].Value;
            entry.DisplayName = _nameInputs[id].Text.Trim();
        }
        _mapping.Save();
        AppendLog("Маппинг кнопок сохранён.");
    }

    private bool _layoutDirty;

    private void OnState(WheelState state)
    {
        VJoy.SetSteer(state.Steer);
        VJoy.SetThrottle(state.Throttle);
        VJoy.SetBrake(state.Brake);

        // Auto-discover any button id we haven't seen before, assigning the
        // next free vJoy button number — no fixed catalogue on the PC side.
        var isNew = _mapping.EnsureEntriesFor(state.Buttons.Keys, state.ButtonLabels);
        if (isNew)
        {
            _mapping.Save();
            UiThread(RefreshMappingPanel);
        }

        foreach (var (id, pressed) in state.Buttons)
        {
            if (_mapping.Entries.TryGetValue(id, out var entry))
                VJoy.SetButton(entry.VjoyButton, pressed);

            if (_pressedDots.TryGetValue(id, out var dot))
            {
                UiThread(() => dot.ForeColor = pressed ? Color.LimeGreen : Color.DimGray);
            }
        }

        UiThread(() =>
        {
            _dashSteer.Text    = $"{state.Steer:+0.00;-0.00}";
            _dashThrottle.Text = $"{state.Throttle * 100:0}%";
            _dashBrake.Text    = $"{state.Brake * 100:0}%";
            _liveValues.Text   = $"Руль {state.Steer:+0.00;-0.00}   Газ {state.Throttle * 100:0}%   Тормоз {state.Brake * 100:0}%";
        });
    }

    private void UpdateDashboardConnection()
    {
        var state = _connectionManager.State;
        var kind  = _connectionManager.ActiveKind;

        _dashMode.Text = kind switch
        {
            TransportKind.WiFi      => "Wi-Fi",
            TransportKind.Usb       => "USB",
            TransportKind.Bluetooth => "Bluetooth",
            _ => "—",
        };
        _dashMode.ForeColor = state == ConnectionState.Connected ? Color.SeaGreen
            : state == ConnectionState.Listening  ? Color.DarkOrange
            : Color.DimGray;

        _dashPeer.Text = state == ConnectionState.Connected
            ? (_connectionManager.PeerDescription ?? "—")
            : (state == ConnectionState.Listening ? "ожидание..." : "не подключен");
        _dashPeer.ForeColor = state == ConnectionState.Connected ? Color.SeaGreen : Color.DimGray;

        _dashUptime.Text = _connectionManager.ConnectedDurationString;
    }

    private void UpdatePingDot(int ms)
    {
        _dashPing.Text = ms < 0 ? "—" : $"{ms} мс";
        _pingDot.BackColor = ms < 0      ? Color.LightGray
            : ms < 20  ? Color.LimeGreen
            : ms < 50  ? Color.YellowGreen
            : ms < 100 ? Color.Orange
            : Color.OrangeRed;
        _dashPing.ForeColor = ms < 0 ? Color.DimGray
            : ms < 50  ? Color.SeaGreen
            : ms < 100 ? Color.DarkOrange
            : Color.Firebrick;
    }

    private void AppendLog(string msg)
    {
        _logBox.AppendText($"[{DateTime.Now:HH:mm:ss}] {msg}\r\n");
    }

    private void UiThread(Action action)
    {
        if (IsDisposed) return;
        if (InvokeRequired) BeginInvoke(action);
        else action();
    }
}
