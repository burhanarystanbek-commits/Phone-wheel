using System.Text.Json;

namespace PhoneWheelPC;

public class MainForm : Form
{
    private readonly WsServer _server = new();
    private Mapping _mapping = Mapping.LoadOrDefault("assetto_corsa");
    private string _presetKey = "assetto_corsa";

    private Label _vjoyStatus = new();
    private Label _serverStatus = new();
    private Label _phoneStatus = new();
    private Label _liveValues = new();
    private ComboBox _presetCombo = new();
    private TextBox _logBox = new();
    private TableLayoutPanel _mappingTable = new();
    private readonly Dictionary<string, NumericUpDown> _mappingInputs = new();
    private Button _fixAclBtn = new();

    public MainForm()
    {
        Text = "PhoneWheel — PC App";
        Width = 620;
        Height = 640;
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 9f);

        BuildUi();

        Load += (_, _) => StartUp();
        FormClosing += (_, _) => { _server.Stop(); VJoy.Shutdown(); };
    }

    private void BuildUi()
    {
        var pad = 12;
        var y = pad;

        var title = new Label { Text = "PhoneWheel", Font = new Font("Segoe UI", 16f, FontStyle.Bold), AutoSize = true, Left = pad, Top = y };
        Controls.Add(title);
        y += 34;

        _vjoyStatus = new Label { Left = pad, Top = y, Width = 580, Height = 20, Text = "vJoy: …" };
        Controls.Add(_vjoyStatus);
        y += 22;

        _serverStatus = new Label { Left = pad, Top = y, Width = 460, Height = 20, Text = "Сервер: …" };
        Controls.Add(_serverStatus);

        _fixAclBtn = new Button { Left = 480, Top = y - 2, Width = 110, Height = 24, Text = "Настроить доступ", Visible = false };
        _fixAclBtn.Click += (_, _) =>
        {
            if (WsServer.TryFixUrlAcl())
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
        };
        Controls.Add(usbBtn);
        y += 32;

        _phoneStatus = new Label { Left = pad, Top = y, Width = 580, Height = 20, Text = "Телефон: не подключен", ForeColor = Color.DarkOrange };
        Controls.Add(_phoneStatus);
        y += 22;

        _liveValues = new Label { Left = pad, Top = y, Width = 580, Height = 20, Text = "Руль: 0   Газ: 0%   Тормоз: 0%" };
        Controls.Add(_liveValues);
        y += 30;

        Controls.Add(new Label { Left = pad, Top = y, Width = 220, Text = "Шаблон игры:", Font = new Font(Font, FontStyle.Bold) });
        y += 20;

        _presetCombo = new ComboBox { Left = pad, Top = y, Width = 260, DropDownStyle = ComboBoxStyle.DropDownList };
        _presetCombo.Items.AddRange(new object[] { "Assetto Corsa", "F1", "iRacing" });
        _presetCombo.SelectedIndex = 0;
        _presetCombo.SelectedIndexChanged += (_, _) => OnPresetChanged();
        Controls.Add(_presetCombo);
        y += 32;

        var hint = new Label { Left = pad, Top = y, Width = 580, Height = 36, ForeColor = Color.Gray, Text = Mapping.GameHint(_presetKey) };
        Controls.Add(hint);
        _hintLabel = hint;
        y += 42;

        Controls.Add(new Label { Left = pad, Top = y, Width = 580, Text = "Кнопка телефона → номер кнопки vJoy (привяжи этот номер в настройках управления игры):", Font = new Font(Font, FontStyle.Bold) });
        y += 22;

        _mappingTable = new TableLayoutPanel
        {
            Left = pad, Top = y, Width = 580, Height = 190,
            ColumnCount = 2, RowCount = Mapping.KnownActions.Length,
        };
        _mappingTable.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 220));
        _mappingTable.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 100));
        foreach (var action in Mapping.KnownActions)
        {
            _mappingTable.Controls.Add(new Label { Text = LabelFor(action), AutoSize = true, Anchor = AnchorStyles.Left, Padding = new Padding(0, 8, 0, 0) });
            var input = new NumericUpDown { Minimum = 1, Maximum = 32, Width = 80, Value = 1 };
            _mappingInputs[action] = input;
            _mappingTable.Controls.Add(input);
        }
        Controls.Add(_mappingTable);
        y += 200;

        var saveBtn = new Button { Left = pad, Top = y, Width = 160, Height = 30, Text = "Сохранить шаблон" };
        saveBtn.Click += (_, _) => SaveMapping();
        Controls.Add(saveBtn);

        var resetBtn = new Button { Left = pad + 170, Top = y, Width = 160, Height = 30, Text = "Сбросить по умолчанию" };
        resetBtn.Click += (_, _) => { _mapping = Mapping.Default(_presetKey); RefreshMappingInputs(); };
        Controls.Add(resetBtn);
        y += 40;

        Controls.Add(new Label { Left = pad, Top = y, Width = 580, Text = "Журнал:", Font = new Font(Font, FontStyle.Bold) });
        y += 20;

        _logBox = new TextBox { Left = pad, Top = y, Width = 580, Height = 70, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical };
        Controls.Add(_logBox);

        RefreshMappingInputs();
    }

    private Label _hintLabel = new();

    private static string LabelFor(string action) => action switch
    {
        "gear_up" => "Передача вверх",
        "gear_down" => "Передача вниз",
        "handbrake" => "Ручник",
        "drs" => "DRS / Push-to-pass",
        "pit_limiter" => "Pit limiter",
        _ => action,
    };

    private void StartUp()
    {
        var ok = VJoy.Initialize();
        _vjoyStatus.Text = $"vJoy: {VJoy.StatusMessage}";
        _vjoyStatus.ForeColor = ok ? Color.SeaGreen : Color.Firebrick;

        _server.StateReceived += OnState;
        _server.ClientConnected += addr => UiThread(() =>
        {
            _phoneStatus.Text = $"Телефон: подключен ({addr})";
            _phoneStatus.ForeColor = Color.SeaGreen;
        });
        _server.ClientDisconnected += () => UiThread(() =>
        {
            _phoneStatus.Text = "Телефон: не подключен";
            _phoneStatus.ForeColor = Color.DarkOrange;
        });
        _server.Log += msg => UiThread(() => AppendLog(msg));

        StartServer();
    }

    private void StartServer()
    {
        _server.Stop();
        var started = _server.Start();
        if (started)
        {
            _serverStatus.Text = $"Сервер: слушает порт {WsServer.Port} (Wi-Fi и USB через adb reverse)";
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

    private void OnPresetChanged()
    {
        _presetKey = _presetCombo.SelectedIndex switch
        {
            1 => "f1",
            2 => "iracing",
            _ => "assetto_corsa",
        };
        _mapping = Mapping.LoadOrDefault(_presetKey);
        _hintLabel.Text = Mapping.GameHint(_presetKey);
        RefreshMappingInputs();
        AppendLog($"Шаблон: {_presetCombo.SelectedItem}");
    }

    private void RefreshMappingInputs()
    {
        foreach (var action in Mapping.KnownActions)
        {
            var val = _mapping.ActionToButton.TryGetValue(action, out var btn) ? btn : 1;
            _mappingInputs[action].Value = Math.Clamp(val, 1, 32);
        }
    }

    private void SaveMapping()
    {
        foreach (var action in Mapping.KnownActions)
            _mapping.ActionToButton[action] = (int)_mappingInputs[action].Value;
        _mapping.Save(_presetKey);
        AppendLog($"Шаблон '{_presetCombo.SelectedItem}' сохранён.");
    }

    private void OnState(WheelState state)
    {
        VJoy.SetSteer(state.Steer);
        VJoy.SetThrottle(state.Throttle);
        VJoy.SetBrake(state.Brake);
        foreach (var action in Mapping.KnownActions)
        {
            var pressed = state.Buttons.TryGetValue(action, out var v) && v;
            if (_mapping.ActionToButton.TryGetValue(action, out var btn))
                VJoy.SetButton(btn, pressed);
        }
        UiThread(() => _liveValues.Text =
            $"Руль: {state.Steer:+0.00;-0.00}   Газ: {state.Throttle * 100:0}%   Тормоз: {state.Brake * 100:0}%");
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
