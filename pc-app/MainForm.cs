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

    public MainForm()
    {
        Text = "PhoneWheel — PC App";
        Width = 640;
        Height = 680;
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

        _vjoyStatus = new Label { Left = pad, Top = y, Width = 600, Height = 20, Text = "vJoy: …" };
        Controls.Add(_vjoyStatus);
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

        _serverStatus = new Label { Left = pad, Top = y, Width = 480, Height = 20, Text = "Сервер: …" };
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

        _phoneStatus = new Label { Left = pad, Top = y, Width = 600, Height = 20, Text = "Телефон: не подключен", ForeColor = Color.DarkOrange };
        Controls.Add(_phoneStatus);
        y += 22;

        _liveValues = new Label { Left = pad, Top = y, Width = 600, Height = 20, Text = "Руль: 0   Газ: 0%   Тормоз: 0%" };
        Controls.Add(_liveValues);
        y += 30;

        var hint = new Label { Left = pad, Top = y, Width = 600, Height = 34, ForeColor = Color.Gray, Text = Mapping.GameHint };
        Controls.Add(hint);
        y += 40;

        Controls.Add(new Label
        {
            Left = pad, Top = y, Width = 600,
            Text = "Кнопки телефона → номер кнопки vJoy и название (привяжи номер в настройках управления игры):",
            Font = new Font(Font, FontStyle.Bold)
        });
        y += 22;

        _emptyHint = new Label
        {
            Left = pad, Top = y, Width = 600, Height = 40,
            ForeColor = Color.Gray,
            Text = "Кнопки появятся здесь автоматически, как только добавишь их на телефоне\n(значок ⚙ → + Добавить кнопку)."
        };
        Controls.Add(_emptyHint);

        _mappingPanel = new FlowLayoutPanel
        {
            Left = pad, Top = y, Width = 600, Height = 220,
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

        Controls.Add(new Label { Left = pad, Top = y, Width = 600, Text = "Журнал:", Font = new Font(Font, FontStyle.Bold) });
        y += 20;

        _logBox = new TextBox { Left = pad, Top = y, Width = 600, Height = 70, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical };
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
            var row = new Panel { Width = 570, Height = 32, Margin = new Padding(2) };

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
        var ok = VJoy.Initialize();
        _vjoyStatus.Text = $"vJoy: {VJoy.StatusMessage}";
        _vjoyStatus.ForeColor = ok ? Color.SeaGreen : Color.Firebrick;

        _connectionManager.StateReceived += OnState;
        _connectionManager.StateChanged += s => UiThread(() => OnConnectionStateChanged(s));
        _connectionManager.Log += msg => UiThread(() => AppendLog(msg));

        StartServer();
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
            case ConnectionState.Reconnecting:
                _phoneStatus.Text = "Телефон: подключение...";
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
            _serverStatus.Text = "Сервер: режим Bluetooth ещё не реализован в этой версии";
            _serverStatus.ForeColor = Color.DarkOrange;
            _fixAclBtn.Visible = false;
            _connectionKindLabel.Text = "";
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

        UiThread(() => _liveValues.Text =
            $"Руль: {state.Steer:+0.00;-0.00}   Газ: {state.Throttle * 100:0}%   Тормоз: {state.Brake * 100:0}%   ({_connectionManager.PacketRateHz} Гц)");
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
