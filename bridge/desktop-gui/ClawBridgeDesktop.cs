using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Forms = System.Windows.Forms;

namespace ClawBridgeDesktop
{
    public class Program
    {
        private static Mutex _mutex;
        private static EventWaitHandle _showEvent;

        [STAThread]
        public static void Main(string[] args)
        {
            bool createdNew;
            _mutex = new Mutex(true, "Local\\ClawBridgeDesktopGuiSingleton", out createdNew);
            _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\ClawBridgeDesktopShowEvent");

            if (!createdNew)
            {
                _showEvent.Set();
                return;
            }

            bool startMinimized = false;
            foreach (string a in args)
            {
                if (string.Equals(a, "--minimized", StringComparison.OrdinalIgnoreCase))
                    startMinimized = true;
            }

            var app = new Application();
            app.ShutdownMode = ShutdownMode.OnExplicitShutdown;
            var win = new MainWindow(startMinimized, _showEvent);
            if (!startMinimized)
            {
                win.Show();
            }
            app.Run();
        }
    }

    public class MainWindow : Window
    {
        private const string IPC_BASE = "http://127.0.0.1:18790";
        private const int HOTKEY_ID = 0xC1AB;
        private const uint MOD_ALT = 0x0001;
        private const uint MOD_CONTROL = 0x0002;
        private const uint VK_C = 0x43;
        private const int WM_HOTKEY = 0x0312;

        [DllImport("user32.dll")]
        private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

        [DllImport("user32.dll")]
        private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        private HwndSource _hwndSource;
        private readonly string _baseDir;
        private readonly string _appDataDir;
        private readonly Forms.NotifyIcon _trayIcon;
        private readonly DispatcherTimer _pollTimer;
        private readonly JavaScriptSerializer _json = new JavaScriptSerializer();

        private bool _allowRealExit = false;
        private bool _launchingDaemon = false;
        private DateTime _lastLaunchAttempt = DateTime.MinValue;
        private string _lastRelayLoaded = "";
        private string _currentPairCode = "";
        private string _currentLanPairCode = "";
        private string _currentLanIp = "";
        private string _activeMode = "wan";
        private string _lastShownBindError = "";

        // Top Big Dual-Mode Segmented Switch (>= 54px button height)
        private Border _segLanBorder;
        private System.Windows.Shapes.Ellipse _segLanDot;
        private TextBlock _segLanTitle;
        private TextBlock _segLanSub;

        private Border _segWanBorder;
        private System.Windows.Shapes.Ellipse _segWanDot;
        private TextBlock _segWanTitle;
        private TextBlock _segWanSub;

        // LAN Alert / Firewall Guidance Banner
        private Border _lanAlertCard;
        private TextBlock _lanAlertTitle;
        private TextBlock _lanAlertBody;

        // Hero Status Card Elements
        private System.Windows.Shapes.Ellipse _statusDot;
        private TextBlock _statusTitle;
        private TextBlock _statusSubtitle;
        private TextBlock _rttBadge;
        private Button _btnConnect;
        private Button _btnDisconnect;
        private Button _btnReconnect;
        private Button _btnCopyLanIp;

        // Pairing / LAN Details Card Elements
        private TextBlock _pairHeader;
        private Border _lanIpBox;
        private TextBlock _lanIpValueText;
        private TextBlock _lanIpPortHint;
        private TextBlock _pairCodeBig;
        private TextBlock _pairSubInfo;
        private Button _btnCopyOrNewCode;
        private Button _btnRefreshLanCode;
        private Button _btnUnpair;

        // Quota & Antigravity Card
        private TextBlock _quotaPlanText;
        private TextBlock _quotaGeminiText;
        private TextBlock _quotaClaudeText;
        private TextBlock _quotaCodexText;
        private TextBlock _cdpText;

        // WAN Relay Config Card
        private Border _wanRelayCfgCard;
        private TextBox _relayUrlBox;
        private TextBox _logBox;

        private Forms.ToolStripMenuItem _trayModeLanItem;
        private Forms.ToolStripMenuItem _trayModeWanItem;
        private Forms.ToolStripMenuItem _trayConnectItem;
        private Forms.ToolStripMenuItem _trayDisconnectItem;

        public MainWindow(bool startMinimized, EventWaitHandle showEvent)
        {
            _baseDir = AppDomain.CurrentDomain.BaseDirectory;
            _appDataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "clawbridge");

            Title = "ClawBridge 桌面控制台 · 双模式 (内网直连 / 外网中继)";
            Width = 820;
            Height = 840;
            MinWidth = 700;
            MinHeight = 640;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Background = BrushHex("#0B0F17");
            Foreground = BrushHex("#E2E8F0");
            FontFamily = new FontFamily("Microsoft YaHei UI");

            string icoPath = Path.Combine(_baseDir, "clawbridge.ico");
            if (File.Exists(icoPath))
            {
                try
                {
                    Icon = System.Windows.Media.Imaging.BitmapFrame.Create(new Uri(icoPath, UriKind.Absolute));
                }
                catch { }
            }

            BuildLayout();

            // Setup System Tray Icon
            _trayIcon = new Forms.NotifyIcon();
            if (File.Exists(icoPath))
            {
                try { _trayIcon.Icon = new System.Drawing.Icon(icoPath); }
                catch { _trayIcon.Icon = System.Drawing.SystemIcons.Application; }
            }
            else
            {
                _trayIcon.Icon = System.Drawing.SystemIcons.Application;
            }
            _trayIcon.Text = "ClawBridge 桌面控制台 (左键呼出 / Ctrl+Alt+C)";
            _trayIcon.Visible = true;
            _trayIcon.MouseClick += (s, e) =>
            {
                if (e.Button == Forms.MouseButtons.Left)
                {
                    ToggleWindowVisibility();
                }
            };
            _trayIcon.DoubleClick += (s, e) => RestoreWindow();

            var menu = new Forms.ContextMenuStrip();
            menu.Items.Add("呼出 / 隐藏控制台 (Ctrl+Alt+C)", null, (s, e) => ToggleWindowVisibility());
            menu.Items.Add(new Forms.ToolStripSeparator());
            _trayModeLanItem = new Forms.ToolStripMenuItem("⚡ 切换至：内网直连模式", null, (s, e) => SwitchConnectionMode("lan"));
            _trayModeWanItem = new Forms.ToolStripMenuItem("🌐 切换至：外网中继模式", null, (s, e) => SwitchConnectionMode("wan"));
            menu.Items.Add(_trayModeLanItem);
            menu.Items.Add(_trayModeWanItem);
            menu.Items.Add(new Forms.ToolStripSeparator());
            _trayConnectItem = new Forms.ToolStripMenuItem("启动 / 连接", null, (s, e) => OnConnectClicked());
            _trayDisconnectItem = new Forms.ToolStripMenuItem("停止 / 断开", null, (s, e) => SendPost("/api/disconnect"));
            menu.Items.Add(_trayConnectItem);
            menu.Items.Add(_trayDisconnectItem);
            menu.Items.Add("立即重连 / 重启监听", null, (s, e) => SendPost("/api/reconnect"));
            menu.Items.Add(new Forms.ToolStripSeparator());
            menu.Items.Add("彻底退出（停止后台桥服务）", null, (s, e) => FullExit());
            _trayIcon.ContextMenuStrip = menu;

            StateChanged += (s, e) =>
            {
                if (WindowState == WindowState.Minimized)
                {
                    Hide();
                }
            };

            Closing += (s, e) =>
            {
                if (!_allowRealExit)
                {
                    e.Cancel = true;
                    Hide();
                }
            };

            // Create hidden HWND early so global Ctrl+Alt+C hotkey works even when launched --minimized
            var helper = new WindowInteropHelper(this);
            IntPtr handle = helper.EnsureHandle();
            _hwndSource = HwndSource.FromHwnd(handle);
            if (_hwndSource != null)
            {
                _hwndSource.AddHook(WndProc);
                RegisterHotKey(handle, HOTKEY_ID, MOD_CONTROL | MOD_ALT, VK_C);
            }

            // Background thread listening for singleton wake-up signal
            ThreadPool.QueueUserWorkItem(_ =>
            {
                while (!_allowRealExit)
                {
                    if (showEvent.WaitOne(1000))
                    {
                        Dispatcher.BeginInvoke(new Action(RestoreWindow));
                    }
                }
            });

            _pollTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1000) };
            _pollTimer.Tick += (s, e) => PollBackend();
            _pollTimer.Start();
            PollBackend();
        }

        private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg == WM_HOTKEY && wParam.ToInt32() == HOTKEY_ID)
            {
                ToggleWindowVisibility();
                handled = true;
            }
            return IntPtr.Zero;
        }

        private void ToggleWindowVisibility()
        {
            if (IsVisible && WindowState != WindowState.Minimized)
            {
                Hide();
            }
            else
            {
                RestoreWindow();
            }
        }

        private void RestoreWindow()
        {
            Show();
            if (WindowState == WindowState.Minimized)
                WindowState = WindowState.Normal;
            Activate();
            Topmost = true;
            Topmost = false;
            Focus();
        }

        private void FullExit()
        {
            _allowRealExit = true;
            _pollTimer.Stop();
            try
            {
                if (_hwndSource != null)
                {
                    UnregisterHotKey(_hwndSource.Handle, HOTKEY_ID);
                    _hwndSource.RemoveHook(WndProc);
                }
            }
            catch { }
            SendPost("/api/shutdown");
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            Application.Current.Shutdown();
        }

        private void SwitchConnectionMode(string targetMode)
        {
            string normalized = string.Equals(targetMode, "lan", StringComparison.OrdinalIgnoreCase) ? "lan" : "wan";
            string token = ReadIpcToken();
            string jsonBody = _json.Serialize(new Dictionary<string, object> { { "mode", normalized } });

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    var req = (HttpWebRequest)WebRequest.Create(IPC_BASE + "/api/mode");
                    req.Proxy = null;
                    req.Method = "POST";
                    req.Timeout = 5000;
                    req.ContentType = "application/json; charset=utf-8";
                    req.Headers["X-ClawBridge-Token"] = token;
                    byte[] bytes = Encoding.UTF8.GetBytes(jsonBody);
                    req.ContentLength = bytes.Length;
                    using (var s = req.GetRequestStream())
                    {
                        s.Write(bytes, 0, bytes.Length);
                    }
                    using (var resp = (HttpWebResponse)req.GetResponse())
                    using (var reader = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                    {
                        string respRaw = reader.ReadToEnd();
                        var resData = _json.Deserialize<Dictionary<string, object>>(respRaw);
                        bool ok = GetBool(resData, "ok");
                        string errText = GetStr(resData, "error");
                        if (normalized == "lan" && (!ok || !string.IsNullOrEmpty(errText)))
                        {
                            Dispatcher.BeginInvoke(new Action(() =>
                            {
                                string msg = string.IsNullOrEmpty(errText)
                                    ? "开启内网直连服务失败，请检查端口 18791 是否被占用或检查 Windows 防火墙设置。"
                                    : (errText + "\n\n如手机无法发现或连接电脑，请在 Windows 防火墙放行 ClawBridge (Node.js) 的专用网络入站（TCP 18791 / UDP 18792）。");
                                MessageBox.Show(msg, "内网直连开启异常", MessageBoxButton.OK, MessageBoxImage.Warning);
                            }));
                        }
                    }
                    Dispatcher.BeginInvoke(new Action(PollBackend));
                }
                catch (Exception ex)
                {
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        MessageBox.Show("切换连接模式失败：" + ex.Message, "切换异常", MessageBoxButton.OK, MessageBoxImage.Error);
                    }));
                }
            });
        }

        private void OnConnectClicked()
        {
            if (_activeMode == "lan")
            {
                SwitchConnectionMode("lan");
            }
            else
            {
                SendPost("/api/connect");
            }
        }

        private void BuildLayout()
        {
            var rootScroll = new ScrollViewer
            {
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Padding = new Thickness(20)
            };

            var stack = new StackPanel();

            // ── 0. Top Prominent Dual-Mode Segmented Switch (Height >= 56px) ──
            var modeSwitchOuter = new Border
            {
                Background = BrushHex("#0F172A"),
                BorderBrush = BrushHex("#334155"),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(6),
                Margin = new Thickness(0, 0, 0, 14)
            };

            var modeGrid = new Grid();
            modeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            modeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(8) });
            modeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            // Segment 1: LAN Direct (内网直连)
            _segLanBorder = new Border
            {
                MinHeight = 58,
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(16, 9, 16, 9),
                Background = BrushHex("#131C2E"),
                BorderBrush = BrushHex("#1E293B"),
                BorderThickness = new Thickness(1.5),
                Cursor = Cursors.Hand
            };
            _segLanBorder.MouseLeftButtonUp += (s, e) => SwitchConnectionMode("lan");

            var segLanRow = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };
            _segLanDot = new System.Windows.Shapes.Ellipse
            {
                Width = 12,
                Height = 12,
                Fill = BrushHex("#64748B"),
                Margin = new Thickness(0, 0, 10, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            var segLanTextCol = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            _segLanTitle = new TextBlock
            {
                Text = "⚡ 内网直连 (LAN)",
                FontSize = 16.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#CBD5E1")
            };
            _segLanSub = new TextBlock
            {
                Text = "未开启 · 点击切换局域网极速直连",
                FontSize = 11.5,
                Foreground = BrushHex("#94A3B8"),
                Margin = new Thickness(0, 2, 0, 0)
            };
            segLanTextCol.Children.Add(_segLanTitle);
            segLanTextCol.Children.Add(_segLanSub);
            segLanRow.Children.Add(_segLanDot);
            segLanRow.Children.Add(segLanTextCol);
            _segLanBorder.Child = segLanRow;
            Grid.SetColumn(_segLanBorder, 0);
            modeGrid.Children.Add(_segLanBorder);

            // Segment 2: WAN Relay (外网中继)
            _segWanBorder = new Border
            {
                MinHeight = 58,
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(16, 9, 16, 9),
                Background = BrushHex("#1E3A8A"),
                BorderBrush = BrushHex("#60A5FA"),
                BorderThickness = new Thickness(1.5),
                Cursor = Cursors.Hand
            };
            _segWanBorder.MouseLeftButtonUp += (s, e) => SwitchConnectionMode("wan");

            var segWanRow = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };
            _segWanDot = new System.Windows.Shapes.Ellipse
            {
                Width = 12,
                Height = 12,
                Fill = BrushHex("#38BDF8"),
                Margin = new Thickness(0, 0, 10, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            var segWanTextCol = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            _segWanTitle = new TextBlock
            {
                Text = "🌐 外网中继 (WAN)",
                FontSize = 16.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#FFFFFF")
            };
            _segWanSub = new TextBlock
            {
                Text = "当前模式 · 检测中继状态中...",
                FontSize = 11.5,
                Foreground = BrushHex("#DBEAFE"),
                Margin = new Thickness(0, 2, 0, 0)
            };
            segWanTextCol.Children.Add(_segWanTitle);
            segWanTextCol.Children.Add(_segWanSub);
            segWanRow.Children.Add(_segWanDot);
            segWanRow.Children.Add(segWanTextCol);
            _segWanBorder.Child = segWanRow;
            Grid.SetColumn(_segWanBorder, 2);
            modeGrid.Children.Add(_segWanBorder);

            modeSwitchOuter.Child = modeGrid;
            stack.Children.Add(modeSwitchOuter);

            // ── 0.5 LAN Port Bind Error / Firewall Guidance Banner ──
            _lanAlertCard = new Border
            {
                Background = BrushHex("#1E293B"),
                BorderBrush = BrushHex("#F59E0B"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(14, 10, 14, 10),
                Margin = new Thickness(0, 0, 0, 14),
                Visibility = Visibility.Collapsed
            };
            var alertDock = new DockPanel();
            var btnOpenFirewall = MakeSmallButton("打开 Windows 防火墙");
            btnOpenFirewall.Margin = new Thickness(10, 0, 0, 0);
            btnOpenFirewall.VerticalAlignment = VerticalAlignment.Center;
            btnOpenFirewall.Click += (s, e) =>
            {
                try
                {
                    Process.Start(new ProcessStartInfo("firewall.cpl") { UseShellExecute = true });
                }
                catch { }
            };
            DockPanel.SetDock(btnOpenFirewall, Dock.Right);
            alertDock.Children.Add(btnOpenFirewall);

            var alertTextStack = new StackPanel();
            _lanAlertTitle = new TextBlock
            {
                Text = "局域网连接提示",
                FontSize = 13,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#FDE68A")
            };
            _lanAlertBody = new TextBlock
            {
                Text = "",
                FontSize = 12,
                Foreground = BrushHex("#E2E8F0"),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 3, 0, 0)
            };
            alertTextStack.Children.Add(_lanAlertTitle);
            alertTextStack.Children.Add(_lanAlertBody);
            alertDock.Children.Add(alertTextStack);
            _lanAlertCard.Child = alertDock;
            stack.Children.Add(_lanAlertCard);

            // ── 1. Hero Connection & Connect/Disconnect Card ──
            var heroCard = MakeCard();
            var heroGrid = new Grid();
            heroGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            heroGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(14) });
            heroGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            var topRow = new DockPanel();
            _rttBadge = new TextBlock
            {
                Text = "-- ms",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 14,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#5CE1E6"),
                VerticalAlignment = VerticalAlignment.Center
            };
            DockPanel.SetDock(_rttBadge, Dock.Right);
            topRow.Children.Add(_rttBadge);

            var statusLeft = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            _statusDot = new System.Windows.Shapes.Ellipse
            {
                Width = 14,
                Height = 14,
                Fill = BrushHex("#FACC15"),
                Margin = new Thickness(0, 0, 10, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            var titleStack = new StackPanel();
            _statusTitle = new TextBlock
            {
                Text = "正在连接本地桌面桥服务...",
                FontSize = 18.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F8FAFC")
            };
            _statusSubtitle = new TextBlock
            {
                Text = "正在初始化后台守护进程",
                FontSize = 12.5,
                Foreground = BrushHex("#94A3B8"),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 3, 0, 0)
            };
            titleStack.Children.Add(_statusTitle);
            titleStack.Children.Add(_statusSubtitle);
            statusLeft.Children.Add(_statusDot);
            statusLeft.Children.Add(titleStack);
            topRow.Children.Add(statusLeft);
            Grid.SetRow(topRow, 0);
            heroGrid.Children.Add(topRow);

            var btnRow = new WrapPanel { Orientation = Orientation.Horizontal };
            _btnConnect = MakeActionButton("▶ 连接中继", "#10B981", "#052E16", true);
            _btnConnect.Click += (s, e) => OnConnectClicked();

            _btnDisconnect = MakeActionButton("■ 断开连接", "#EF4444", "#FFFFFF", true);
            _btnDisconnect.Click += (s, e) => SendPost("/api/disconnect");

            _btnReconnect = MakeActionButton("↻ 立即重连", "#1E293B", "#E2E8F0", false);
            _btnReconnect.Click += (s, e) => SendPost("/api/reconnect");

            _btnCopyLanIp = MakeActionButton("复制本机局域网 IP", "#0F766E", "#FFFFFF", false);
            _btnCopyLanIp.Visibility = Visibility.Collapsed;
            _btnCopyLanIp.Click += (s, e) =>
            {
                if (!string.IsNullOrEmpty(_currentLanIp))
                {
                    Clipboard.SetText(_currentLanIp);
                    _statusSubtitle.Text = "已复制本机局域网 IP (" + _currentLanIp + ") 到剪贴板，可在手机端手动输入直连";
                }
            };

            var btnMinTray = MakeActionButton("最小化到托盘", "#1E293B", "#94A3B8", false);
            btnMinTray.Click += (s, e) => Hide();

            btnRow.Children.Add(_btnConnect);
            btnRow.Children.Add(_btnDisconnect);
            btnRow.Children.Add(_btnReconnect);
            btnRow.Children.Add(_btnCopyLanIp);
            btnRow.Children.Add(btnMinTray);
            Grid.SetRow(btnRow, 2);
            heroGrid.Children.Add(btnRow);

            heroCard.Child = heroGrid;
            stack.Children.Add(heroCard);

            // ── 2. Two-Column Row: Pairing/LAN Card + Quota Card ──
            var midGrid = new Grid { Margin = new Thickness(0, 14, 0, 0) };
            midGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.08, GridUnitType.Star) });
            midGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(14) });
            midGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.12, GridUnitType.Star) });

            // Left: Mode-Adaptive Pairing & LAN Address Card
            var pairCard = MakeCard();
            var pairStack = new StackPanel();
            _pairHeader = new TextBlock
            {
                Text = "手机配对与绑定状态",
                FontSize = 15,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F1F5F9")
            };

            // LAN IP Box (shown in LAN mode)
            _lanIpBox = new Border
            {
                Background = BrushHex("#0B1320"),
                BorderBrush = BrushHex("#1E3A5F"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(10, 7, 10, 7),
                Margin = new Thickness(0, 8, 0, 2),
                Visibility = Visibility.Collapsed
            };
            var lanIpStack = new StackPanel();
            _lanIpValueText = new TextBlock
            {
                Text = "本机局域网 IP：读取中...",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 14,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#34D399")
            };
            _lanIpPortHint = new TextBlock
            {
                Text = "直连端口：18791 · UDP 自动发现：18792",
                FontSize = 11.5,
                Foreground = BrushHex("#94A3B8"),
                Margin = new Thickness(0, 2, 0, 0)
            };
            lanIpStack.Children.Add(_lanIpValueText);
            lanIpStack.Children.Add(_lanIpPortHint);
            _lanIpBox.Child = lanIpStack;

            _pairCodeBig = new TextBlock
            {
                Text = "--- ---",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 28,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#5CE1E6"),
                Margin = new Thickness(0, 10, 0, 6)
            };
            _pairSubInfo = new TextBlock
            {
                Text = "正在读取配对信息...",
                FontSize = 12,
                Foreground = BrushHex("#94A3B8"),
                TextWrapping = TextWrapping.Wrap
            };
            var pairBtns = new WrapPanel { Margin = new Thickness(0, 12, 0, 0) };
            _btnCopyOrNewCode = MakeSmallButton("复制配对码");
            _btnCopyOrNewCode.Click += (s, e) =>
            {
                string activeCode = _activeMode == "lan" ? _currentLanPairCode : _currentPairCode;
                if (!string.IsNullOrEmpty(activeCode))
                {
                    Clipboard.SetText(activeCode);
                    _pairSubInfo.Text = "已复制 6 位配对码 " + activeCode + " 到剪贴板！";
                }
                else if (_activeMode == "lan")
                {
                    SendPost("/api/lan/new-pair-code");
                }
                else
                {
                    SendPost("/api/new-pair-code");
                }
            };

            _btnRefreshLanCode = MakeSmallButton("↻ 刷新内网配对码");
            _btnRefreshLanCode.Visibility = Visibility.Collapsed;
            _btnRefreshLanCode.Click += (s, e) => SendPost("/api/lan/new-pair-code");

            _btnUnpair = MakeSmallButton("刷新配对码 / 重新绑定");
            _btnUnpair.Click += (s, e) =>
            {
                if (_activeMode == "lan")
                {
                    if (MessageBox.Show("确定要重置内网配对并签发新的 6 位内网配对码吗？（不影响外网中继配对）", "重置内网配对确认", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
                    {
                        SendPost("/api/lan/unpair");
                    }
                }
                else
                {
                    if (MessageBox.Show("确定要重新生成外网 6 位配对码吗？（若当前已绑定手机，将解除旧外网绑定，不影响内网配对）", "重新配对确认", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
                    {
                        SendPost("/api/unpair");
                        SendPost("/api/new-pair-code");
                    }
                }
            };
            pairBtns.Children.Add(_btnCopyOrNewCode);
            pairBtns.Children.Add(_btnRefreshLanCode);
            pairBtns.Children.Add(_btnUnpair);

            pairStack.Children.Add(_pairHeader);
            pairStack.Children.Add(_lanIpBox);
            pairStack.Children.Add(_pairCodeBig);
            pairStack.Children.Add(_pairSubInfo);
            pairStack.Children.Add(pairBtns);
            pairCard.Child = pairStack;
            Grid.SetColumn(pairCard, 0);
            midGrid.Children.Add(pairCard);

            // Right: Quota & Antigravity Status Card
            var quotaCard = MakeCard();
            var quotaStack = new StackPanel();
            var quotaHeaderRow = new DockPanel();
            var btnRefreshQuota = MakeSmallButton("刷新配额");
            btnRefreshQuota.Margin = new Thickness(0);
            btnRefreshQuota.Click += (s, e) => SendPost("/api/refresh-quota");
            DockPanel.SetDock(btnRefreshQuota, Dock.Right);
            quotaHeaderRow.Children.Add(btnRefreshQuota);
            quotaHeaderRow.Children.Add(new TextBlock
            {
                Text = "模型配额与本机通道",
                FontSize = 15,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F1F5F9"),
                VerticalAlignment = VerticalAlignment.Center
            });

            _quotaPlanText = new TextBlock
            {
                Text = "套餐：读取中...",
                FontSize = 12.5,
                Foreground = BrushHex("#5CE1E6"),
                FontWeight = FontWeights.SemiBold,
                Margin = new Thickness(0, 8, 0, 6)
            };
            _quotaGeminiText = new TextBlock
            {
                Text = "Gemini：5h --% · 7d --%",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 13,
                Foreground = BrushHex("#E2E8F0"),
                Margin = new Thickness(0, 2, 0, 4)
            };
            _quotaClaudeText = new TextBlock
            {
                Text = "AGY 附赠池：5h --% · 7d --%",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 12.5,
                Foreground = BrushHex("#E2E8F0"),
                Margin = new Thickness(0, 0, 0, 4)
            };
            _quotaCodexText = new TextBlock
            {
                Text = "Codex(OpenAI)：5h --% · 7d --%",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 12.5,
                Foreground = BrushHex("#C4B5FD"),
                Margin = new Thickness(0, 0, 0, 8)
            };
            _cdpText = new TextBlock
            {
                Text = "通道：实时 Transcript 镜像运行中",
                FontSize = 11.5,
                Foreground = BrushHex("#94A3B8"),
                TextWrapping = TextWrapping.Wrap
            };

            var btnRestartCdp = MakeSmallButton("以 9222 调试模式重启 Antigravity (可选兜底)");
            btnRestartCdp.Margin = new Thickness(0, 6, 0, 0);
            btnRestartCdp.Click += (s, e) =>
            {
                if (MessageBox.Show("确定要重启当前 Antigravity 并附加 --remote-debugging-port=9222 调试端口吗？\n（平时 agentapi 主通道已可直接双向收发，无需重启；仅需 DOM 级控制时使用）", "重启接管确认", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
                {
                    SendPost("/api/launch-antigravity-cdp");
                }
            };

            quotaStack.Children.Add(quotaHeaderRow);
            quotaStack.Children.Add(_quotaPlanText);
            quotaStack.Children.Add(_quotaGeminiText);
            quotaStack.Children.Add(_quotaClaudeText);
            quotaStack.Children.Add(_quotaCodexText);
            quotaStack.Children.Add(_cdpText);
            quotaStack.Children.Add(btnRestartCdp);
            quotaCard.Child = quotaStack;
            Grid.SetColumn(quotaCard, 2);
            midGrid.Children.Add(quotaCard);

            stack.Children.Add(midGrid);

            // ── 3. WAN Relay URL Configuration Card ──
            _wanRelayCfgCard = MakeCard();
            _wanRelayCfgCard.Margin = new Thickness(0, 14, 0, 0);
            var cfgStack = new StackPanel();
            cfgStack.Children.Add(new TextBlock
            {
                Text = "外网中继服务器地址（仅外网模式使用，可随时修改切换）",
                FontSize = 14,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F1F5F9"),
                Margin = new Thickness(0, 0, 0, 8)
            });

            var cfgRow = new Grid();
            cfgRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            cfgRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(10) });
            cfgRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            cfgRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(8) });
            cfgRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            _relayUrlBox = new TextBox
            {
                Text = "wss://relay.example.com",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 13.5,
                Padding = new Thickness(10, 7, 10, 7),
                Background = BrushHex("#0B0F17"),
                Foreground = BrushHex("#F8FAFC"),
                BorderBrush = BrushHex("#334155"),
                BorderThickness = new Thickness(1),
                VerticalContentAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(_relayUrlBox, 0);
            cfgRow.Children.Add(_relayUrlBox);

            var btnSaveRelay = MakeActionButton("保存并应用", "#0284C7", "#FFFFFF", false);
            btnSaveRelay.Margin = new Thickness(0);
            btnSaveRelay.Click += (s, e) =>
            {
                string u = (_relayUrlBox.Text ?? "").Trim();
                if (!u.StartsWith("wss://") && !u.StartsWith("ws://"))
                {
                    MessageBox.Show("中继地址须以 wss:// 开头", "格式提示", MessageBoxButton.OK, MessageBoxImage.Warning);
                    return;
                }
                string body = _json.Serialize(new Dictionary<string, object> { { "relayUrl", u } });
                SendPost("/api/relay-config", body);
            };
            Grid.SetColumn(btnSaveRelay, 2);
            cfgRow.Children.Add(btnSaveRelay);

            var btnOpenDir = MakeSmallButton("打开配置目录");
            btnOpenDir.Margin = new Thickness(0);
            btnOpenDir.Click += (s, e) =>
            {
                Directory.CreateDirectory(_appDataDir);
                Process.Start("explorer.exe", _appDataDir);
            };
            Grid.SetColumn(btnOpenDir, 4);
            cfgRow.Children.Add(btnOpenDir);

            cfgStack.Children.Add(cfgRow);
            _wanRelayCfgCard.Child = cfgStack;
            stack.Children.Add(_wanRelayCfgCard);

            // ── 3.5 Push Notification & Offline Queue Test Card ──
            var testCard = MakeCard();
            testCard.Margin = new Thickness(0, 14, 0, 0);
            var testStack = new StackPanel();
            testStack.Children.Add(new TextBlock
            {
                Text = "手机推送联调与离线暂存测试（内网 / 外网双模式通用）",
                FontSize = 14,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F1F5F9"),
                Margin = new Thickness(0, 0, 0, 8)
            });
            var testBtns = new WrapPanel();
            var bLow = MakeSmallButton("⏳ 推送【待确认·低风险】");
            bLow.Click += (s, e) => SendPost("/api/test-notify", "{\"kind\":\"approval_low\"}");
            var bHigh = MakeSmallButton("⚠️ 推送【待确认·高风险(需PIN)】");
            bHigh.Click += (s, e) => SendPost("/api/test-notify", "{\"kind\":\"approval_high\"}");
            var bDone = MakeSmallButton("✅ 推送【任务完成】");
            bDone.Click += (s, e) => SendPost("/api/test-notify", "{\"kind\":\"task_done\"}");
            var bErr = MakeSmallButton("❌ 推送【任务报错】");
            bErr.Click += (s, e) => SendPost("/api/test-notify", "{\"kind\":\"task_error\"}");
            var bSeq = MakeActionButton("📨 连发 3 条有序通知 (#1→#2→#3)", "#4F46E5", "#FFFFFF", false);
            bSeq.Padding = new Thickness(12, 5, 12, 5);
            bSeq.FontSize = 12;
            bSeq.Margin = new Thickness(0, 0, 8, 4);
            bSeq.Click += (s, e) => SendPost("/api/test-notify", "{\"kind\":\"sequence_3\"}");

            testBtns.Children.Add(bLow);
            testBtns.Children.Add(bHigh);
            testBtns.Children.Add(bDone);
            testBtns.Children.Add(bErr);
            testBtns.Children.Add(bSeq);
            testStack.Children.Add(testBtns);
            testCard.Child = testStack;
            stack.Children.Add(testCard);

            // ── 4. Live Sanitized Logs Card ──
            var logCard = MakeCard();
            logCard.Margin = new Thickness(0, 14, 0, 0);
            var logStack = new StackPanel();
            logStack.Children.Add(new TextBlock
            {
                Text = "实时运行日志（已脱敏保护 Token）",
                FontSize = 14,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F1F5F9"),
                Margin = new Thickness(0, 0, 0, 8)
            });
            _logBox = new TextBox
            {
                Height = 155,
                IsReadOnly = true,
                TextWrapping = TextWrapping.NoWrap,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
                FontFamily = new FontFamily("Consolas"),
                FontSize = 11.5,
                Background = BrushHex("#070A10"),
                Foreground = BrushHex("#94A3B8"),
                BorderBrush = BrushHex("#1E293B"),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(8)
            };
            logStack.Children.Add(_logBox);
            logCard.Child = logStack;
            stack.Children.Add(logCard);

            rootScroll.Content = stack;
            Content = rootScroll;
        }

        private Border MakeCard()
        {
            return new Border
            {
                Background = BrushHex("#131A26"),
                BorderBrush = BrushHex("#1E293B"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(16)
            };
        }

        private Button MakeActionButton(string label, string bgHex, string fgHex, bool bold)
        {
            return new Button
            {
                Content = label,
                Padding = new Thickness(16, 8, 16, 8),
                Margin = new Thickness(0, 0, 10, 0),
                Background = BrushHex(bgHex),
                Foreground = BrushHex(fgHex),
                BorderThickness = new Thickness(0),
                FontSize = 13.5,
                FontWeight = bold ? FontWeights.Bold : FontWeights.Medium,
                Cursor = Cursors.Hand
            };
        }

        private Button MakeSmallButton(string label)
        {
            return new Button
            {
                Content = label,
                Padding = new Thickness(10, 5, 10, 5),
                Margin = new Thickness(0, 0, 8, 4),
                Background = BrushHex("#1E293B"),
                Foreground = BrushHex("#E2E8F0"),
                BorderBrush = BrushHex("#334155"),
                BorderThickness = new Thickness(1),
                FontSize = 12,
                Cursor = Cursors.Hand
            };
        }

        private static SolidColorBrush BrushHex(string hex)
        {
            var c = (Color)ColorConverter.ConvertFromString(hex);
            var b = new SolidColorBrush(c);
            b.Freeze();
            return b;
        }

        private string ReadIpcToken()
        {
            string p = Path.Combine(_appDataDir, "ipc.token");
            if (!File.Exists(p)) return "";
            try { return File.ReadAllText(p, Encoding.UTF8).Trim(); }
            catch { return ""; }
        }

        private static string ResolveNodeExePath()
        {
            string[] candidates = new string[]
            {
                @"C:\Program Files\nodejs\node.exe",
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "nodejs", "node.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "nodejs", "node.exe"),
                "node.exe"
            };
            foreach (string c in candidates)
            {
                if (c == "node.exe" || File.Exists(c)) return c;
            }
            return "node.exe";
        }

        private void EnsureBackendRunning()
        {
            if (_launchingDaemon || (DateTime.UtcNow - _lastLaunchAttempt).TotalSeconds < 4)
                return;

            _launchingDaemon = true;
            _lastLaunchAttempt = DateTime.UtcNow;

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string scriptPath = Path.Combine(_baseDir, "src", "index.mjs");
                    if (!File.Exists(scriptPath))
                    {
                        Dispatcher.BeginInvoke(new Action(() => ShowBackendStarting("未找到后台脚本: " + scriptPath)));
                        return;
                    }

                    string nodeExe = ResolveNodeExePath();
                    var psi = new ProcessStartInfo
                    {
                        FileName = nodeExe,
                        Arguments = "--no-warnings \"" + scriptPath + "\"",
                        WorkingDirectory = _baseDir,
                        CreateNoWindow = true,
                        UseShellExecute = false,
                        WindowStyle = ProcessWindowStyle.Hidden
                    };
                    Process.Start(psi);
                }
                catch (Exception ex)
                {
                    Dispatcher.BeginInvoke(new Action(() => ShowBackendStarting("后台引擎拉起异常: " + ex.Message)));
                }
                finally
                {
                    _launchingDaemon = false;
                }
            });
        }

        private void SendPost(string path, string jsonBody = "{}")
        {
            string token = ReadIpcToken();
            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    var req = (HttpWebRequest)WebRequest.Create(IPC_BASE + path);
                    req.Proxy = null;
                    req.Method = "POST";
                    req.Timeout = 4000;
                    req.ContentType = "application/json; charset=utf-8";
                    req.Headers["X-ClawBridge-Token"] = token;
                    byte[] bytes = Encoding.UTF8.GetBytes(jsonBody);
                    req.ContentLength = bytes.Length;
                    using (var s = req.GetRequestStream())
                    {
                        s.Write(bytes, 0, bytes.Length);
                    }
                    using (var resp = (HttpWebResponse)req.GetResponse()) { }
                    Dispatcher.BeginInvoke(new Action(PollBackend));
                }
                catch { }
            });
        }

        private void PollBackend()
        {
            string token = ReadIpcToken();
            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    var req = (HttpWebRequest)WebRequest.Create(IPC_BASE + "/api/status");
                    req.Proxy = null;
                    req.Method = "GET";
                    req.Timeout = 1800;
                    req.Headers["X-ClawBridge-Token"] = token;
                    using (var resp = (HttpWebResponse)req.GetResponse())
                    using (var reader = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                    {
                        string raw = reader.ReadToEnd();
                        var data = _json.Deserialize<Dictionary<string, object>>(raw);
                        Dispatcher.BeginInvoke(new Action(() =>
                        {
                            try { ApplyStatus(data); }
                            catch (Exception uiEx) { ShowBackendStarting("界面同步异常: " + uiEx.Message); }
                        }));
                    }
                }
                catch (WebException wex)
                {
                    var httpResp = wex.Response as HttpWebResponse;
                    if (httpResp != null && httpResp.StatusCode == HttpStatusCode.Forbidden)
                    {
                        Dispatcher.BeginInvoke(new Action(() => ShowBackendStarting("本地 IPC 令牌校验未匹配，重试中...")));
                    }
                    else
                    {
                        EnsureBackendRunning();
                        Dispatcher.BeginInvoke(new Action(() => ShowBackendStarting("本地服务自检中，正在静默拉起后台引擎...")));
                    }
                }
                catch (Exception ex)
                {
                    EnsureBackendRunning();
                    Dispatcher.BeginInvoke(new Action(() => ShowBackendStarting("自检重试中：" + ex.Message)));
                }
            });
        }

        private void ShowBackendStarting(string detail = "本地服务自检中，通常仅需 1~2 秒")
        {
            _statusDot.Fill = BrushHex("#FACC15");
            _statusTitle.Text = "正在启动桌面桥后台引擎...";
            _statusSubtitle.Text = detail;
            _rttBadge.Text = "-- ms";
        }

        private void ApplyStatus(Dictionary<string, object> d)
        {
            string modeRaw = GetStr(d, "connectionMode");
            bool lanEnabled = GetBool(d, "lanEnabled") || string.Equals(modeRaw, "lan", StringComparison.OrdinalIgnoreCase);
            _activeMode = lanEnabled ? "lan" : "wan";

            // WAN fields
            bool manualPaused = GetBool(d, "manualPaused");
            bool connected = GetBool(d, "connected");
            bool authenticated = GetBool(d, "authenticated");
            bool paired = GetBool(d, "paired");
            bool peerOnline = GetBool(d, "peerOnline");
            string pairedName = GetStr(d, "pairedDeviceName");
            string relayUrl = GetStr(d, "relayUrl");
            string pairCode = GetStr(d, "pairingCode");
            long pairExp = GetLong(d, "pairingExpiresAt");
            int rtt = GetInt(d, "lastRttMs", -1);

            // LAN fields
            bool lanRunning = GetBool(d, "lanRunning");
            int lanPort = GetInt(d, "lanPort", 18791);
            int lanDiscoveryPort = GetInt(d, "lanDiscoveryPort", 18792);
            string lanPrimaryIp = GetStr(d, "lanPrimaryIp");
            string lanBindError = GetStr(d, "lanBindError");
            string lanUdpWarning = GetStr(d, "lanUdpWarning");
            bool lanPaired = GetBool(d, "lanPaired");
            string lanPairedName = GetStr(d, "lanPairedDeviceName");
            string lanPhoneIp = GetStr(d, "lanConnectedPhoneIp");
            bool lanPeerOnline = GetBool(d, "lanPeerOnline");
            int lanRtt = GetInt(d, "lanLastRttMs", -1);
            string lanPairCode = GetStr(d, "lanPairingCode");
            long lanPairExp = GetLong(d, "lanPairingExpiresAt");
            string peerMode = GetStr(d, "peerConnectionMode");

            var allIpsList = new List<string>();
            if (d.ContainsKey("lanIps") && d["lanIps"] is System.Collections.ArrayList)
            {
                foreach (object item in (System.Collections.ArrayList)d["lanIps"])
                {
                    if (item != null && !string.IsNullOrEmpty(item.ToString()))
                        allIpsList.Add(item.ToString());
                }
            }
            string allIpsText = allIpsList.Count > 0 ? string.Join(" / ", allIpsList.ToArray()) : lanPrimaryIp;

            _currentPairCode = pairCode;
            _currentLanPairCode = lanPairCode;
            _currentLanIp = lanPrimaryIp;

            if (_lastRelayLoaded != relayUrl && !string.IsNullOrEmpty(relayUrl) && !_relayUrlBox.IsFocused)
            {
                _lastRelayLoaded = relayUrl;
                _relayUrlBox.Text = relayUrl;
            }

            // ── Update Top Big Segmented Switch ──
            _trayModeLanItem.Checked = lanEnabled;
            _trayModeWanItem.Checked = !lanEnabled;

            if (lanEnabled)
            {
                _segLanBorder.Background = BrushHex("#0F766E");
                _segLanBorder.BorderBrush = BrushHex("#2DD4BF");
                _segLanTitle.Foreground = BrushHex("#FFFFFF");
                _segLanSub.Foreground = BrushHex("#CCFBF1");

                if (!string.IsNullOrEmpty(lanBindError))
                {
                    _segLanDot.Fill = BrushHex("#EF4444");
                    _segLanSub.Text = "端口绑定失败 · 点击重试";
                }
                else if (lanRunning && lanPeerOnline)
                {
                    _segLanDot.Fill = BrushHex("#22C55E");
                    _segLanSub.Text = "服务已开启 · 手机已直连 (" + (string.IsNullOrEmpty(lanPhoneIp) ? "局域网" : lanPhoneIp) + ")";
                }
                else if (lanRunning)
                {
                    _segLanDot.Fill = BrushHex("#38BDF8");
                    _segLanSub.Text = "服务已开启 · 等待手机局域网直连";
                }
                else
                {
                    _segLanDot.Fill = BrushHex("#F59E0B");
                    _segLanSub.Text = "服务已暂停 · 点击下方启动";
                }

                _segWanBorder.Background = BrushHex("#131C2E");
                _segWanBorder.BorderBrush = BrushHex("#1E293B");
                _segWanTitle.Foreground = BrushHex("#94A3B8");
                _segWanSub.Foreground = BrushHex("#64748B");
                _segWanDot.Fill = BrushHex("#475569");
                _segWanSub.Text = "已停用（点击一键切回外网中继）";
            }
            else
            {
                _segWanBorder.Background = BrushHex("#1D4ED8");
                _segWanBorder.BorderBrush = BrushHex("#60A5FA");
                _segWanTitle.Foreground = BrushHex("#FFFFFF");
                _segWanSub.Foreground = BrushHex("#DBEAFE");

                if (manualPaused)
                {
                    _segWanDot.Fill = BrushHex("#94A3B8");
                    _segWanSub.Text = "外网中继 · 已手动断开";
                }
                else if (connected && authenticated && peerOnline)
                {
                    _segWanDot.Fill = BrushHex("#22C55E");
                    _segWanSub.Text = "中继已连接 · 手机在线";
                }
                else if (connected && authenticated)
                {
                    _segWanDot.Fill = BrushHex("#38BDF8");
                    _segWanSub.Text = "中继已连接 · 等待手机上线";
                }
                else
                {
                    _segWanDot.Fill = BrushHex("#F59E0B");
                    _segWanSub.Text = "中继未连接 · 正在重连...";
                }

                _segLanBorder.Background = BrushHex("#131C2E");
                _segLanBorder.BorderBrush = BrushHex("#1E293B");
                _segLanTitle.Foreground = BrushHex("#94A3B8");
                _segLanSub.Foreground = BrushHex("#64748B");
                _segLanDot.Fill = BrushHex("#475569");
                _segLanSub.Text = "未开启（点击切换局域网极速直连）";
            }

            // ── Update LAN Mode vs WAN Mode Hero & Pairing Panels ──
            if (lanEnabled)
            {
                _wanRelayCfgCard.Opacity = 0.6;
                _lanIpBox.Visibility = Visibility.Visible;
                _btnCopyLanIp.Visibility = string.IsNullOrEmpty(lanPrimaryIp) ? Visibility.Collapsed : Visibility.Visible;

                _lanIpValueText.Text = string.IsNullOrEmpty(allIpsText)
                    ? "本机局域网 IP：未检测到活动局域网网卡"
                    : ("本机局域网 IP：" + allIpsText);
                _lanIpPortHint.Text = string.Format("WebSocket 端口：{0}  ·  UDP 自动发现端口：{1}", lanPort, lanDiscoveryPort);

                // Alert Banner: Port Error OR Firewall Guidance
                if (!string.IsNullOrEmpty(lanBindError))
                {
                    _lanAlertCard.Visibility = Visibility.Visible;
                    _lanAlertCard.Background = BrushHex("#2A1215");
                    _lanAlertCard.BorderBrush = BrushHex("#EF4444");
                    _lanAlertTitle.Foreground = BrushHex("#FCA5A5");
                    _lanAlertTitle.Text = "⚠️ 内网直连端口绑定失败";
                    _lanAlertBody.Text = lanBindError + "；若被防火墙拦截，请在 Windows 防火墙放行 ClawBridge (Node.js) 的专用网络入站。";

                    if (_lastShownBindError != lanBindError)
                    {
                        _lastShownBindError = lanBindError;
                    }
                }
                else if (!string.IsNullOrEmpty(lanUdpWarning))
                {
                    _lanAlertCard.Visibility = Visibility.Visible;
                    _lanAlertCard.Background = BrushHex("#292012");
                    _lanAlertCard.BorderBrush = BrushHex("#F59E0B");
                    _lanAlertTitle.Foreground = BrushHex("#FDE68A");
                    _lanAlertTitle.Text = "⚠️ 局域网自动发现受限";
                    _lanAlertBody.Text = lanUdpWarning + "。手机端可直接手动输入本机 IP " + lanPrimaryIp + " 直连，或在 Windows 防火墙放行专用网络入站。";
                }
                else if (lanRunning && !lanPeerOnline)
                {
                    _lanAlertCard.Visibility = Visibility.Visible;
                    _lanAlertCard.Background = BrushHex("#0F1E2E");
                    _lanAlertCard.BorderBrush = BrushHex("#0284C7");
                    _lanAlertTitle.Foreground = BrushHex("#7DD3FC");
                    _lanAlertTitle.Text = "💡 内网直连就绪提示";
                    _lanAlertBody.Text = "请确认手机与电脑连接同一路由器/WiFi，并在手机端顶部切至「内网直连」。若手机发现超时，请在 Windows 防火墙放行 Node.js 的专用网络入站（TCP " + lanPort + " / UDP " + lanDiscoveryPort + "）或在手机端手动输入 IP " + (string.IsNullOrEmpty(lanPrimaryIp) ? "" : lanPrimaryIp) + "。";
                }
                else
                {
                    _lanAlertCard.Visibility = Visibility.Collapsed;
                    _lastShownBindError = "";
                }

                // Hero Card in LAN Mode
                _rttBadge.Text = (lanRunning && lanPeerOnline && lanRtt >= 0) ? (lanRtt + " ms") : "-- ms";
                _btnConnect.Content = "▶ 启动内网监听";
                _btnDisconnect.Content = "■ 停止监听";
                _btnReconnect.Content = "↻ 重启内网服务";

                if (!string.IsNullOrEmpty(lanBindError))
                {
                    _statusDot.Fill = BrushHex("#EF4444");
                    _statusTitle.Text = "内网服务启动失败";
                    _statusSubtitle.Text = lanBindError;
                    _btnConnect.IsEnabled = true;
                    _btnConnect.Opacity = 1.0;
                    _btnDisconnect.IsEnabled = false;
                    _btnDisconnect.Opacity = 0.45;
                    _trayIcon.Text = "ClawBridge · 内网端口异常";
                }
                else if (lanRunning && lanPeerOnline)
                {
                    _statusDot.Fill = BrushHex("#22C55E");
                    _statusTitle.Text = "内网直连已建立 · 手机实时在线";
                    string peerModeNote = string.IsNullOrEmpty(peerMode) ? "" : (" · 对端模式：" + peerMode.ToUpperInvariant());
                    _statusSubtitle.Text = string.Format("直连设备：{0} ({1}) · 本机 {2}:{3}{4}",
                        string.IsNullOrEmpty(lanPairedName) ? "局域网手机" : lanPairedName,
                        string.IsNullOrEmpty(lanPhoneIp) ? "LAN" : lanPhoneIp,
                        string.IsNullOrEmpty(lanPrimaryIp) ? "0.0.0.0" : lanPrimaryIp,
                        lanPort,
                        peerModeNote);
                    _btnConnect.IsEnabled = false;
                    _btnConnect.Opacity = 0.45;
                    _btnDisconnect.IsEnabled = true;
                    _btnDisconnect.Opacity = 1.0;
                    _trayIcon.Text = "ClawBridge · 内网直连 (手机在线)";
                }
                else if (lanRunning)
                {
                    _statusDot.Fill = BrushHex("#38BDF8");
                    _statusTitle.Text = lanPaired ? "内网服务已开启 · 等待已配对手机直连" : "内网服务已开启 · 等待手机输入内网配对码";
                    _statusSubtitle.Text = string.Format("监听 ws://{0}:{1}（不经过外网中继，外网断开亦可正常收发）",
                        string.IsNullOrEmpty(lanPrimaryIp) ? "0.0.0.0" : lanPrimaryIp, lanPort);
                    _btnConnect.IsEnabled = false;
                    _btnConnect.Opacity = 0.45;
                    _btnDisconnect.IsEnabled = true;
                    _btnDisconnect.Opacity = 1.0;
                    _trayIcon.Text = "ClawBridge · 内网直连待机";
                }
                else
                {
                    _statusDot.Fill = BrushHex("#64748B");
                    _statusTitle.Text = "内网监听已手动停止";
                    _statusSubtitle.Text = "点击「启动内网监听」恢复局域网服务，或点击顶部切回「外网中继」";
                    _btnConnect.IsEnabled = true;
                    _btnConnect.Opacity = 1.0;
                    _btnDisconnect.IsEnabled = false;
                    _btnDisconnect.Opacity = 0.45;
                    _trayIcon.Text = "ClawBridge · 内网监听已停止";
                }

                _trayConnectItem.Enabled = _btnConnect.IsEnabled;
                _trayDisconnectItem.Enabled = _btnDisconnect.IsEnabled;

                // Pairing Card in LAN Mode
                _pairHeader.Text = "内网直连配对与设备（与外网配对独立）";
                _btnUnpair.Content = "解除内网绑定 / 重置";

                bool hasActiveLanCode = !string.IsNullOrEmpty(lanPairCode) && lanPairCode.Length == 6;
                if (hasActiveLanCode)
                {
                    _pairCodeBig.FontSize = 28;
                    _pairCodeBig.Foreground = BrushHex("#34D399");
                    _pairCodeBig.Text = lanPairCode.Substring(0, 3) + " " + lanPairCode.Substring(3, 3);
                    long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    long remSec = Math.Max(0, (lanPairExp - nowMs) / 1000);
                    string pairedExtra = lanPaired ? ("（原绑定：" + (string.IsNullOrEmpty(lanPairedName) ? "已配对手机" : lanPairedName) + "）") : "";
                    _pairSubInfo.Text = string.Format("内网 6 位配对码剩余有效时间：{0:D2}:{1:D2} {2}", remSec / 60, remSec % 60, pairedExtra);
                    _btnCopyOrNewCode.Content = "复制内网配对码";
                    _btnCopyOrNewCode.Visibility = Visibility.Visible;
                    _btnRefreshLanCode.Content = "↻ 刷新配对码";
                    _btnRefreshLanCode.Visibility = lanRunning ? Visibility.Visible : Visibility.Collapsed;
                }
                else if (lanPaired)
                {
                    _pairCodeBig.Text = string.IsNullOrEmpty(lanPairedName) ? "已配对内网手机" : lanPairedName;
                    _pairCodeBig.FontSize = 20;
                    _pairCodeBig.Foreground = BrushHex("#22C55E");
                    _pairSubInfo.Text = lanPeerOnline
                        ? ("状态：🟢 局域网直连在线 (" + lanPhoneIp + ")，低延迟双向通信中")
                        : "状态：⚪ 已完成内网配对，手机切至「内网直连」即可免输码秒连";
                    _btnCopyOrNewCode.Visibility = Visibility.Collapsed;
                    _btnRefreshLanCode.Content = "生成新配对码 (换机)";
                    _btnRefreshLanCode.Visibility = lanRunning ? Visibility.Visible : Visibility.Collapsed;
                }
                else
                {
                    _pairCodeBig.FontSize = 24;
                    _pairCodeBig.Foreground = BrushHex("#5CE1E6");
                    _pairCodeBig.Text = lanRunning ? "生成中..." : "未启动";
                    _pairSubInfo.Text = lanRunning ? "正在签发 6 位内网配对码..." : "请先启动内网监听以生成配对码";
                    _btnCopyOrNewCode.Visibility = Visibility.Collapsed;
                    _btnRefreshLanCode.Visibility = lanRunning ? Visibility.Visible : Visibility.Collapsed;
                }
            }
            else
            {
                // ── WAN Relay Mode (Preserves 100% of existing WAN behavior) ──
                _wanRelayCfgCard.Opacity = 1.0;
                _lanAlertCard.Visibility = Visibility.Collapsed;
                _lanIpBox.Visibility = Visibility.Collapsed;
                _btnCopyLanIp.Visibility = Visibility.Collapsed;
                _btnRefreshLanCode.Visibility = Visibility.Collapsed;

                _btnConnect.Content = "▶ 连接中继";
                _btnDisconnect.Content = "■ 断开连接";
                _btnReconnect.Content = "↻ 立即重连";
                _pairHeader.Text = "外网中继配对状态（与内网配对独立）";
                _btnUnpair.Content = "刷新配对码 / 重新绑定";

                _rttBadge.Text = (connected && rtt >= 0) ? (rtt + " ms") : "-- ms";

                if (manualPaused)
                {
                    _statusDot.Fill = BrushHex("#64748B");
                    _statusTitle.Text = "已手动断开外网中继";
                    _statusSubtitle.Text = "当前已切断与中继服务器的通信（手机端将显示桌面离线）";
                    _btnConnect.IsEnabled = true;
                    _btnConnect.Opacity = 1.0;
                    _btnDisconnect.IsEnabled = false;
                    _btnDisconnect.Opacity = 0.45;
                    _trayConnectItem.Enabled = true;
                    _trayDisconnectItem.Enabled = false;
                    _trayIcon.Text = "ClawBridge · 外网已手动断开";
                }
                else if (connected && authenticated)
                {
                    if (paired && peerOnline)
                    {
                        _statusDot.Fill = BrushHex("#22C55E");
                        _statusTitle.Text = "已连接外网中继 · 手机实时在线";
                        _statusSubtitle.Text = "已连通 " + relayUrl + " · 绑定设备：" + (string.IsNullOrEmpty(pairedName) ? "安卓手机" : pairedName);
                        _trayIcon.Text = "ClawBridge · 外网已连接 (手机在线)";
                    }
                    else if (paired)
                    {
                        _statusDot.Fill = BrushHex("#38BDF8");
                        _statusTitle.Text = "已连接外网中继 · 等待手机上线";
                        _statusSubtitle.Text = "已连通 " + relayUrl + " · 手机打开 App 即刻同步";
                        _trayIcon.Text = "ClawBridge · 外网已连接 (手机未在线)";
                    }
                    else
                    {
                        _statusDot.Fill = BrushHex("#FACC15");
                        _statusTitle.Text = "已连接外网中继 · 等待配对绑定";
                        _statusSubtitle.Text = "请在手机 App 外网模式下输入下方 6 位一次性配对码";
                        _trayIcon.Text = "ClawBridge · 等待手机配对";
                    }
                    _btnConnect.IsEnabled = false;
                    _btnConnect.Opacity = 0.45;
                    _btnDisconnect.IsEnabled = true;
                    _btnDisconnect.Opacity = 1.0;
                    _trayConnectItem.Enabled = false;
                    _trayDisconnectItem.Enabled = true;
                }
                else
                {
                    _statusDot.Fill = BrushHex("#F59E0B");
                    _statusTitle.Text = "正在连接外网中继服务器...";
                    _statusSubtitle.Text = "目标：" + relayUrl;
                    _btnConnect.IsEnabled = false;
                    _btnConnect.Opacity = 0.45;
                    _btnDisconnect.IsEnabled = true;
                    _btnDisconnect.Opacity = 1.0;
                    _trayConnectItem.Enabled = false;
                    _trayDisconnectItem.Enabled = true;
                    _trayIcon.Text = "ClawBridge · 正在连接中继...";
                }

                if (paired)
                {
                    _pairCodeBig.Text = string.IsNullOrEmpty(pairedName) ? "已绑定手机" : pairedName;
                    _pairCodeBig.FontSize = 20;
                    _pairCodeBig.Foreground = BrushHex("#22C55E");
                    _pairSubInfo.Text = peerOnline ? "状态：🟢 手机当前在线，会话与配额实时同步中" : "状态：⚪ 手机暂未在前台，事件通知将经中继暂存推送";
                    _btnCopyOrNewCode.Visibility = Visibility.Collapsed;
                }
                else
                {
                    _pairCodeBig.FontSize = 28;
                    _pairCodeBig.Foreground = BrushHex("#5CE1E6");
                    if (!string.IsNullOrEmpty(pairCode) && pairCode.Length == 6)
                    {
                        _pairCodeBig.Text = pairCode.Substring(0, 3) + " " + pairCode.Substring(3, 3);
                        long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                        long remSec = Math.Max(0, (pairExp - nowMs) / 1000);
                        _pairSubInfo.Text = string.Format("一次性配对码剩余有效时间：{0:D2}:{1:D2}", remSec / 60, remSec % 60);
                        _btnCopyOrNewCode.Content = "复制配对码";
                        _btnCopyOrNewCode.Visibility = Visibility.Visible;
                    }
                    else
                    {
                        _pairCodeBig.Text = manualPaused ? "已断开" : "生成中...";
                        _pairSubInfo.Text = manualPaused ? "请先点击上方「连接中继」获取配对码" : "连接中继后自动签发 6 位配对码";
                        _btnCopyOrNewCode.Visibility = Visibility.Collapsed;
                    }
                }
            }

            // Quota Card (Shared across both modes)
            if (d.ContainsKey("quota") && d["quota"] is Dictionary<string, object>)
            {
                var q = (Dictionary<string, object>)d["quota"];
                _quotaPlanText.Text = "反重力套餐：" + GetStr(q, "planName");
                _quotaGeminiText.Text = string.Format("AGY Gemini  5h 剩余 {0}%  |  7d 剩余 {1}%",
                    GetInt(q, "gemini5hRem", 0), GetInt(q, "gemini7dRem", 0));
                _quotaClaudeText.Text = string.Format("AGY 附赠池  5h 剩余 {0}%  |  7d 剩余 {1}%",
                    GetInt(q, "claude5hRem", 0), GetInt(q, "claude7dRem", 0));
            }
            if (d.ContainsKey("codexQuota") && d["codexQuota"] is Dictionary<string, object>)
            {
                var cq = (Dictionary<string, object>)d["codexQuota"];
                _quotaCodexText.Text = string.Format("Codex({0}) 5h 剩余 {1}%  |  7d 剩余 {2}%",
                    GetStr(cq, "planName"), GetInt(cq, "window5hRem", 0), GetInt(cq, "window7dRem", 0));
            }
            string cdpNote = GetStr(d, "cdpNote");
            _cdpText.Text = !string.IsNullOrEmpty(cdpNote)
                ? ("本机通道：🟢 " + cdpNote)
                : "本机通道：🟢 Transcript 实时镜像运行中";

            // Logs
            if (d.ContainsKey("logs") && d["logs"] is System.Collections.ArrayList)
            {
                var arr = (System.Collections.ArrayList)d["logs"];
                var sb = new StringBuilder();
                foreach (object item in arr)
                {
                    if (item != null) sb.AppendLine(item.ToString());
                }
                string newLogs = sb.ToString();
                if (_logBox.Text != newLogs)
                {
                    _logBox.Text = newLogs;
                    _logBox.ScrollToEnd();
                }
            }
        }

        private static bool GetBool(Dictionary<string, object> d, string k)
        {
            return d != null && d.ContainsKey(k) && d[k] is bool && (bool)d[k];
        }

        private static string GetStr(Dictionary<string, object> d, string k)
        {
            return (d != null && d.ContainsKey(k) && d[k] != null) ? d[k].ToString() : "";
        }

        private static int GetInt(Dictionary<string, object> d, string k, int defVal)
        {
            if (d == null || !d.ContainsKey(k) || d[k] == null) return defVal;
            int v;
            return int.TryParse(d[k].ToString(), out v) ? v : defVal;
        }

        private static long GetLong(Dictionary<string, object> d, string k)
        {
            if (d == null || !d.ContainsKey(k) || d[k] == null) return 0L;
            long v;
            return long.TryParse(d[k].ToString(), out v) ? v : 0L;
        }
    }
}
