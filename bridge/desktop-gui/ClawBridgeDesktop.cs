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
        private DateTime _lastLaunchAttempt = DateTime.UtcNow.AddSeconds(-10);
        private string _lastRelayLoaded = "";
        private string _currentWanPairCode = "";
        private string _currentLanPairCode = "";
        private string _currentLanIp = "";
        private bool _lanCurrentlyEnabled = true;

        // Top Dual-Standby Hero Summary
        private Border _heroBannerBorder;
        private System.Windows.Shapes.Ellipse _heroDot;
        private TextBlock _heroTitle;
        private TextBlock _heroSubtitle;
        private Button _btnRestoreDual;

        // Left Channel Card: LAN Direct (Default Always ON, Port 18791)
        private Border _lanCardBorder;
        private System.Windows.Shapes.Ellipse _lanDot;
        private TextBlock _lanStateTitle;
        private TextBlock _lanRttBadge;
        private TextBlock _lanIpText;
        private TextBlock _lanPortText;
        private TextBlock _lanAlertText;
        private TextBlock _lanPairBig;
        private TextBlock _lanPairSub;
        private Button _btnCopyLanIp;
        private Button _btnCopyLanCode;
        private Button _btnRefreshLanCode;
        private Button _btnUnpairLan;
        private Button _btnToggleLan;
        private Button _btnOpenFirewall;

        // Right Channel Card: WAN Relay (Default Always ON, WSS)
        private Border _wanCardBorder;
        private System.Windows.Shapes.Ellipse _wanDot;
        private TextBlock _wanStateTitle;
        private TextBlock _wanRttBadge;
        private TextBlock _wanEndpointText;
        private TextBlock _wanStateSub;
        private TextBlock _wanPairBig;
        private TextBlock _wanPairSub;
        private Button _btnCopyWanCode;
        private Button _btnUnpairWan;
        private Button _btnWanConnect;
        private Button _btnWanDisconnect;
        private Button _btnWanReconnect;

        // Quota & Antigravity Card
        private TextBlock _quotaPlanText;
        private TextBlock _quotaGeminiText;
        private TextBlock _quotaClaudeText;
        private TextBlock _quotaCodexText;
        private TextBlock _cdpText;

        // WAN Relay URL Config Card
        private TextBox _relayUrlBox;
        private CheckBox _chkTrustSelfSigned;
        private TextBox _logBox;

        private Forms.ToolStripMenuItem _trayLanToggleItem;
        private Forms.ToolStripMenuItem _trayWanConnectItem;
        private Forms.ToolStripMenuItem _trayWanDisconnectItem;

        public MainWindow(bool startMinimized, EventWaitHandle showEvent)
        {
            _baseDir = AppDomain.CurrentDomain.BaseDirectory;
            _appDataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "clawbridge");

            Title = "ClawBridge 桌面控制台 v2.1 · 内外网双通道常开待命";
            Width = 880;
            Height = 860;
            MinWidth = 740;
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
            _trayIcon.Text = "ClawBridge 桌面控制台 · 双通道待命 (Ctrl+Alt+C)";
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
            menu.Items.Add("⚡+🌐 一键确保内外网双通道全开", null, (s, e) => EnableBothChannels());
            _trayLanToggleItem = new Forms.ToolStripMenuItem("暂停 / 开启内网直连监听 (18791)", null, (s, e) => ToggleLanListener());
            menu.Items.Add(_trayLanToggleItem);
            menu.Items.Add(new Forms.ToolStripSeparator());
            _trayWanConnectItem = new Forms.ToolStripMenuItem("连接外网中继", null, (s, e) => SendPost("/api/connect"));
            _trayWanDisconnectItem = new Forms.ToolStripMenuItem("暂停外网中继", null, (s, e) => SendPost("/api/disconnect"));
            menu.Items.Add(_trayWanConnectItem);
            menu.Items.Add(_trayWanDisconnectItem);
            menu.Items.Add("立即重连外网中继", null, (s, e) => SendPost("/api/reconnect"));
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

            var helper = new WindowInteropHelper(this);
            IntPtr handle = helper.EnsureHandle();
            _hwndSource = HwndSource.FromHwnd(handle);
            if (_hwndSource != null)
            {
                _hwndSource.AddHook(WndProc);
                RegisterHotKey(handle, HOTKEY_ID, MOD_CONTROL | MOD_ALT, VK_C);
            }

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

        private void EnableBothChannels()
        {
            SendPost("/api/mode", "{\"mode\":\"dual\"}");
            SendPost("/api/connect");
        }

        private void ToggleLanListener()
        {
            bool target = !_lanCurrentlyEnabled;
            string body = _json.Serialize(new Dictionary<string, object> { { "enabled", target } });
            SendPost("/api/lan/toggle", body);
        }

        private void BuildLayout()
        {
            var rootScroll = new ScrollViewer
            {
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Padding = new Thickness(18)
            };

            var stack = new StackPanel();

            // ── 0. Top Dual-Channel Standby Banner ──
            _heroBannerBorder = new Border
            {
                Background = BrushHex("#102033"),
                BorderBrush = BrushHex("#2DD4BF"),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(16, 13, 16, 13),
                Margin = new Thickness(0, 0, 0, 14)
            };

            var heroDock = new DockPanel();
            var heroBtns = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            _btnRestoreDual = MakeActionButton("⚡+🌐 恢复双通道全开", "#0F766E", "#FFFFFF", true);
            _btnRestoreDual.Visibility = Visibility.Collapsed;
            _btnRestoreDual.Click += (s, e) => EnableBothChannels();

            var btnMinTray = MakeSmallButton("最小化到托盘");
            btnMinTray.Margin = new Thickness(0);
            btnMinTray.Click += (s, e) => Hide();

            heroBtns.Children.Add(_btnRestoreDual);
            heroBtns.Children.Add(btnMinTray);
            DockPanel.SetDock(heroBtns, Dock.Right);
            heroDock.Children.Add(heroBtns);

            var heroLeft = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            _heroDot = new System.Windows.Shapes.Ellipse
            {
                Width = 14,
                Height = 14,
                Fill = BrushHex("#34D399"),
                Margin = new Thickness(0, 0, 12, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            var heroTextCol = new StackPanel();
            _heroTitle = new TextBlock
            {
                Text = "正在连接本地桥后台引擎...",
                FontSize = 17.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F8FAFC")
            };
            _heroSubtitle = new TextBlock
            {
                Text = "电脑端默认同时开启「内网直连 (18791)」与「外网中继」，无需在电脑切换，直接在手机端选择访问方式即可",
                FontSize = 12,
                Foreground = BrushHex("#94A3B8"),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 3, 0, 0)
            };
            heroTextCol.Children.Add(_heroTitle);
            heroTextCol.Children.Add(_heroSubtitle);
            heroLeft.Children.Add(_heroDot);
            heroLeft.Children.Add(heroTextCol);
            heroDock.Children.Add(heroLeft);

            _heroBannerBorder.Child = heroDock;
            stack.Children.Add(_heroBannerBorder);

            // ── 1. Side-by-Side Dual Active Channel Cards (LAN Direct + WAN Relay) ──
            var dualGrid = new Grid();
            dualGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            dualGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(14) });
            dualGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            // ── Left Card: LAN Direct Channel (Port 18791 + UDP 18792) ──
            _lanCardBorder = MakeCard();
            var lanStack = new StackPanel();

            var lanTopRow = new DockPanel();
            _lanRttBadge = new TextBlock
            {
                Text = "-- ms",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 13,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#34D399"),
                VerticalAlignment = VerticalAlignment.Center
            };
            DockPanel.SetDock(_lanRttBadge, Dock.Right);
            lanTopRow.Children.Add(_lanRttBadge);

            var lanTitleRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            _lanDot = new System.Windows.Shapes.Ellipse
            {
                Width = 11,
                Height = 11,
                Fill = BrushHex("#38BDF8"),
                Margin = new Thickness(0, 0, 8, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            _lanStateTitle = new TextBlock
            {
                Text = "⚡ 内网直连通道（默认常开）",
                FontSize = 15.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F1F5F9")
            };
            lanTitleRow.Children.Add(_lanDot);
            lanTitleRow.Children.Add(_lanStateTitle);
            lanTopRow.Children.Add(lanTitleRow);
            lanStack.Children.Add(lanTopRow);

            var lanIpBox = new Border
            {
                Background = BrushHex("#0B1320"),
                BorderBrush = BrushHex("#1E3A5F"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(10, 7, 10, 7),
                Margin = new Thickness(0, 10, 0, 6)
            };
            var lanIpStack = new StackPanel();
            _lanIpText = new TextBlock
            {
                Text = "本机局域网 IP：检测中...",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 13.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#34D399")
            };
            _lanPortText = new TextBlock
            {
                Text = "直连监听：ws://0.0.0.0:18791 · UDP 发现：18792",
                FontSize = 11.5,
                Foreground = BrushHex("#94A3B8"),
                Margin = new Thickness(0, 2, 0, 0)
            };
            lanIpStack.Children.Add(_lanIpText);
            lanIpStack.Children.Add(_lanPortText);
            lanIpBox.Child = lanIpStack;
            lanStack.Children.Add(lanIpBox);

            _lanAlertText = new TextBlock
            {
                Text = "",
                FontSize = 11.5,
                Foreground = BrushHex("#FCA5A5"),
                TextWrapping = TextWrapping.Wrap,
                Visibility = Visibility.Collapsed,
                Margin = new Thickness(0, 2, 0, 4)
            };
            lanStack.Children.Add(_lanAlertText);

            _lanPairBig = new TextBlock
            {
                Text = "--- ---",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 24,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#34D399"),
                Margin = new Thickness(0, 6, 0, 4)
            };
            _lanPairSub = new TextBlock
            {
                Text = "读取内网配对状态...",
                FontSize = 12,
                Foreground = BrushHex("#94A3B8"),
                TextWrapping = TextWrapping.Wrap
            };
            lanStack.Children.Add(_lanPairBig);
            lanStack.Children.Add(_lanPairSub);

            var lanBtns = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
            _btnCopyLanIp = MakeSmallButton("复制本机 IP");
            _btnCopyLanIp.Click += (s, e) =>
            {
                if (!string.IsNullOrEmpty(_currentLanIp))
                {
                    Clipboard.SetText(_currentLanIp);
                    _lanPairSub.Text = "已复制本机局域网 IP (" + _currentLanIp + ") 到剪贴板";
                }
            };

            _btnCopyLanCode = MakeSmallButton("复制内网码");
            _btnCopyLanCode.Click += (s, e) =>
            {
                if (!string.IsNullOrEmpty(_currentLanPairCode))
                {
                    Clipboard.SetText(_currentLanPairCode);
                    _lanPairSub.Text = "已复制内网 6 位配对码 " + _currentLanPairCode + " 到剪贴板！";
                }
            };

            _btnRefreshLanCode = MakeSmallButton("生成/刷新内网码");
            _btnRefreshLanCode.Click += (s, e) => SendPost("/api/lan/new-pair-code");

            _btnUnpairLan = MakeSmallButton("解绑内网设备");
            _btnUnpairLan.Click += (s, e) =>
            {
                if (MessageBox.Show("确定要解除当前内网手机绑定并生成新的 6 位内网配对码吗？（不影响外网配对）", "解绑内网确认", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
                {
                    SendPost("/api/lan/unpair");
                }
            };

            _btnToggleLan = MakeSmallButton("暂停内网监听");
            _btnToggleLan.Click += (s, e) => ToggleLanListener();

            _btnOpenFirewall = MakeSmallButton("防火墙设置");
            _btnOpenFirewall.Click += (s, e) =>
            {
                try { Process.Start(new ProcessStartInfo("firewall.cpl") { UseShellExecute = true }); }
                catch { }
            };

            lanBtns.Children.Add(_btnCopyLanIp);
            lanBtns.Children.Add(_btnCopyLanCode);
            lanBtns.Children.Add(_btnRefreshLanCode);
            lanBtns.Children.Add(_btnUnpairLan);
            lanBtns.Children.Add(_btnToggleLan);
            lanBtns.Children.Add(_btnOpenFirewall);
            lanStack.Children.Add(lanBtns);

            _lanCardBorder.Child = lanStack;
            Grid.SetColumn(_lanCardBorder, 0);
            dualGrid.Children.Add(_lanCardBorder);

            // ── Right Card: WAN Relay Channel (WSS) ──
            _wanCardBorder = MakeCard();
            var wanStack = new StackPanel();

            var wanTopRow = new DockPanel();
            _wanRttBadge = new TextBlock
            {
                Text = "-- ms",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 13,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#60A5FA"),
                VerticalAlignment = VerticalAlignment.Center
            };
            DockPanel.SetDock(_wanRttBadge, Dock.Right);
            wanTopRow.Children.Add(_wanRttBadge);

            var wanTitleRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            _wanDot = new System.Windows.Shapes.Ellipse
            {
                Width = 11,
                Height = 11,
                Fill = BrushHex("#38BDF8"),
                Margin = new Thickness(0, 0, 8, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            _wanStateTitle = new TextBlock
            {
                Text = "🌐 外网中继通道（默认常开）",
                FontSize = 15.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F1F5F9")
            };
            wanTitleRow.Children.Add(_wanDot);
            wanTitleRow.Children.Add(_wanStateTitle);
            wanTopRow.Children.Add(wanTitleRow);
            wanStack.Children.Add(wanTopRow);

            var wanUrlBoxBorder = new Border
            {
                Background = BrushHex("#0B1320"),
                BorderBrush = BrushHex("#1E293B"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(10, 7, 10, 7),
                Margin = new Thickness(0, 10, 0, 6)
            };
            var wanUrlInfoStack = new StackPanel();
            _wanEndpointText = new TextBlock
            {
                Text = "中继地址：读取中...",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 13,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#93C5FD"),
                TextTrimming = TextTrimming.CharacterEllipsis
            };
            _wanStateSub = new TextBlock
            {
                Text = "检测外网中继连接状态...",
                FontSize = 11.5,
                Foreground = BrushHex("#94A3B8"),
                Margin = new Thickness(0, 2, 0, 0)
            };
            wanUrlInfoStack.Children.Add(_wanEndpointText);
            wanUrlInfoStack.Children.Add(_wanStateSub);
            wanUrlBoxBorder.Child = wanUrlInfoStack;
            wanStack.Children.Add(wanUrlBoxBorder);

            _wanPairBig = new TextBlock
            {
                Text = "--- ---",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 24,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#60A5FA"),
                Margin = new Thickness(0, 6, 0, 4)
            };
            _wanPairSub = new TextBlock
            {
                Text = "读取外网配对状态...",
                FontSize = 12,
                Foreground = BrushHex("#94A3B8"),
                TextWrapping = TextWrapping.Wrap
            };
            wanStack.Children.Add(_wanPairBig);
            wanStack.Children.Add(_wanPairSub);

            var wanBtns = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
            _btnCopyWanCode = MakeSmallButton("复制外网码");
            _btnCopyWanCode.Click += (s, e) =>
            {
                if (!string.IsNullOrEmpty(_currentWanPairCode))
                {
                    Clipboard.SetText(_currentWanPairCode);
                    _wanPairSub.Text = "已复制外网 6 位配对码 " + _currentWanPairCode + " 到剪贴板！";
                }
                else
                {
                    SendPost("/api/new-pair-code");
                }
            };

            _btnUnpairWan = MakeSmallButton("刷新码/重绑外网");
            _btnUnpairWan.Click += (s, e) =>
            {
                if (MessageBox.Show("确定要重新生成外网 6 位配对码吗？（若当前已绑定外网手机，将解除旧外网绑定，不影响内网配对）", "重绑外网确认", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
                {
                    SendPost("/api/unpair");
                    SendPost("/api/new-pair-code");
                }
            };

            _btnWanConnect = MakeSmallButton("▶ 连接外网");
            _btnWanConnect.Click += (s, e) => SendPost("/api/connect");

            _btnWanDisconnect = MakeSmallButton("■ 暂停外网");
            _btnWanDisconnect.Click += (s, e) => SendPost("/api/disconnect");

            _btnWanReconnect = MakeSmallButton("↻ 重连外网");
            _btnWanReconnect.Click += (s, e) => SendPost("/api/reconnect");

            wanBtns.Children.Add(_btnCopyWanCode);
            wanBtns.Children.Add(_btnUnpairWan);
            wanBtns.Children.Add(_btnWanConnect);
            wanBtns.Children.Add(_btnWanDisconnect);
            wanBtns.Children.Add(_btnWanReconnect);
            wanStack.Children.Add(wanBtns);

            _wanCardBorder.Child = wanStack;
            Grid.SetColumn(_wanCardBorder, 2);
            dualGrid.Children.Add(_wanCardBorder);

            stack.Children.Add(dualGrid);

            // ── 2. Quota & Antigravity Local Channel Card ──
            var quotaCard = MakeCard();
            quotaCard.Margin = new Thickness(0, 14, 0, 0);
            var quotaStack = new StackPanel();
            var quotaHeaderRow = new DockPanel();

            var quotaBtnsRight = new StackPanel { Orientation = Orientation.Horizontal };
            var btnRefreshQuota = MakeSmallButton("刷新配额");
            btnRefreshQuota.Margin = new Thickness(0, 0, 8, 0);
            btnRefreshQuota.Click += (s, e) => SendPost("/api/refresh-quota");

            var btnRestartCdp = MakeSmallButton("以 9222 调试模式重启 Antigravity (可选兜底)");
            btnRestartCdp.Margin = new Thickness(0);
            btnRestartCdp.Click += (s, e) =>
            {
                if (MessageBox.Show("确定要重启当前 Antigravity 并附加 --remote-debugging-port=9222 调试端口吗？\n（平时 agentapi 主通道已可直接双向收发，无需重启）", "重启接管确认", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
                {
                    SendPost("/api/launch-antigravity-cdp");
                }
            };
            quotaBtnsRight.Children.Add(btnRefreshQuota);
            quotaBtnsRight.Children.Add(btnRestartCdp);
            DockPanel.SetDock(quotaBtnsRight, Dock.Right);
            quotaHeaderRow.Children.Add(quotaBtnsRight);

            quotaHeaderRow.Children.Add(new TextBlock
            {
                Text = "模型配额与本机 AI 引擎通道",
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

            var quotaItemsWrap = new WrapPanel();
            _quotaGeminiText = new TextBlock
            {
                Text = "Gemini：5h --% · 7d --%",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 13,
                Foreground = BrushHex("#E2E8F0"),
                Margin = new Thickness(0, 0, 24, 4)
            };
            _quotaClaudeText = new TextBlock
            {
                Text = "AGY 附赠池：5h --% · 7d --%",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 13,
                Foreground = BrushHex("#E2E8F0"),
                Margin = new Thickness(0, 0, 24, 4)
            };
            _quotaCodexText = new TextBlock
            {
                Text = "Codex(OpenAI)：5h --% · 7d --%",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 13,
                Foreground = BrushHex("#C4B5FD"),
                Margin = new Thickness(0, 0, 0, 4)
            };
            quotaItemsWrap.Children.Add(_quotaGeminiText);
            quotaItemsWrap.Children.Add(_quotaClaudeText);
            quotaItemsWrap.Children.Add(_quotaCodexText);

            _cdpText = new TextBlock
            {
                Text = "本机通道：实时 Transcript 镜像运行中",
                FontSize = 11.5,
                Foreground = BrushHex("#94A3B8"),
                Margin = new Thickness(0, 4, 0, 0)
            };

            quotaStack.Children.Add(quotaHeaderRow);
            quotaStack.Children.Add(_quotaPlanText);
            quotaStack.Children.Add(quotaItemsWrap);
            quotaStack.Children.Add(_cdpText);
            quotaCard.Child = quotaStack;
            stack.Children.Add(quotaCard);

            // ── 3. WAN Relay URL Configuration Card ──
            var cfgCard = MakeCard();
            cfgCard.Margin = new Thickness(0, 14, 0, 0);
            var cfgStack = new StackPanel();
            cfgStack.Children.Add(new TextBlock
            {
                Text = "外网中继服务器地址（支持直接填 VPS 公网 IPv4:端口 免域名直连，仅用内网可不填）",
                FontSize = 14,
                FontWeight = FontWeights.Bold,
                Foreground = BrushHex("#F1F5F9"),
                Margin = new Thickness(0, 0, 0, 4)
            });
            cfgStack.Children.Add(new TextBlock
            {
                Text = "示例：直接填 203.0.113.10:8443（自动补全 wss:// 并开启自签 TLS 兼容），或填域名 wss://relay.yourdomain.com",
                FontSize = 11.5,
                Foreground = BrushHex("#94A3B8"),
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

            _chkTrustSelfSigned = new CheckBox
            {
                Content = "信任自签 TLS 证书（使用 VPS 公网 IP 免域名直连时勾选，输入纯 IP 保存时将自动开启）",
                Foreground = BrushHex("#94A3B8"),
                FontSize = 12,
                Margin = new Thickness(0, 8, 0, 0),
                VerticalContentAlignment = VerticalAlignment.Center
            };

            var btnSaveRelay = MakeActionButton("保存并应用", "#0284C7", "#FFFFFF", false);
            btnSaveRelay.Margin = new Thickness(0);
            btnSaveRelay.Click += (s, e) =>
            {
                string rawInput = (_relayUrlBox.Text ?? "").Trim().TrimEnd('/');
                if (string.IsNullOrEmpty(rawInput))
                {
                    MessageBox.Show("请输入 VPS 公网 IP:端口（如 203.0.113.10:8443）或 wss:// 域名地址", "格式提示", MessageBoxButton.OK, MessageBoxImage.Warning);
                    return;
                }
                string u = rawInput;
                if (u.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                    u = "wss://" + u.Substring(8);
                else if (u.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
                    u = "ws://" + u.Substring(7);
                else if (!u.StartsWith("wss://", StringComparison.OrdinalIgnoreCase) && !u.StartsWith("ws://", StringComparison.OrdinalIgnoreCase))
                    u = "wss://" + u;

                _relayUrlBox.Text = u;
                bool isBareIp = IsBareIpv4RelayUrl(u);
                bool trustCert = isBareIp ? true : (_chkTrustSelfSigned.IsChecked == true);
                if (isBareIp) _chkTrustSelfSigned.IsChecked = true;

                var payload = new Dictionary<string, object>
                {
                    { "relayUrl", u },
                    { "trustSelfSigned", trustCert }
                };
                SendPost("/api/relay-config", _json.Serialize(payload));
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
            cfgStack.Children.Add(_chkTrustSelfSigned);
            cfgCard.Child = cfgStack;
            stack.Children.Add(cfgCard);

            // ── 3.5 Push Notification Test Card ──
            var testCard = MakeCard();
            testCard.Margin = new Thickness(0, 14, 0, 0);
            var testStack = new StackPanel();
            testStack.Children.Add(new TextBlock
            {
                Text = "手机推送联调测试（自动走手机当前在线通道：内网直连优先，外网兜底缓冲）",
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
                Height = 145,
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
                BorderThickness = new Thickness(1.2),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(15)
            };
        }

        private Button MakeActionButton(string label, string bgHex, string fgHex, bool bold)
        {
            return new Button
            {
                Content = label,
                Padding = new Thickness(14, 7, 14, 7),
                Margin = new Thickness(0, 0, 10, 0),
                Background = BrushHex(bgHex),
                Foreground = BrushHex(fgHex),
                BorderThickness = new Thickness(0),
                FontSize = 13,
                FontWeight = bold ? FontWeights.Bold : FontWeights.Medium,
                Cursor = Cursors.Hand
            };
        }

        private Button MakeSmallButton(string label)
        {
            return new Button
            {
                Content = label,
                Padding = new Thickness(9, 5, 9, 5),
                Margin = new Thickness(0, 0, 6, 5),
                Background = BrushHex("#1E293B"),
                Foreground = BrushHex("#E2E8F0"),
                BorderBrush = BrushHex("#334155"),
                BorderThickness = new Thickness(1),
                FontSize = 11.5,
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
                        Dispatcher.BeginInvoke(new Action(() => ShowBackendStarting("本地 IPC 令牌校验同步中...")));
                    }
                    else
                    {
                        EnsureBackendRunning();
                        Dispatcher.BeginInvoke(new Action(() => ShowBackendStarting("本地守护进程启动中，通常仅需 1~2 秒...")));
                    }
                }
                catch (Exception ex)
                {
                    EnsureBackendRunning();
                    Dispatcher.BeginInvoke(new Action(() => ShowBackendStarting("自检重试中：" + ex.Message)));
                }
            });
        }

        private void ShowBackendStarting(string detail)
        {
            _heroDot.Fill = BrushHex("#FACC15");
            _heroTitle.Text = "正在启动桌面桥后台引擎...";
            _heroSubtitle.Text = detail;
        }

        private void ApplyStatus(Dictionary<string, object> d)
        {
            // WAN fields
            bool wanManualPaused = GetBool(d, "manualPaused");
            bool wanConnected = GetBool(d, "connected");
            bool wanAuthenticated = GetBool(d, "authenticated");
            bool wanPaired = GetBool(d, "paired");
            bool wanPeerOnline = GetBool(d, "peerOnline");
            string wanPairedName = GetStr(d, "pairedDeviceName");
            string relayUrl = GetStr(d, "relayUrl");
            bool trustSelfSigned = GetBool(d, "trustSelfSigned");
            string wanPairCode = GetStr(d, "pairingCode");
            long wanPairExp = GetLong(d, "pairingExpiresAt");
            int wanRtt = GetInt(d, "lastRttMs", -1);
            bool wanUnconfigured = string.IsNullOrEmpty(relayUrl) || relayUrl.IndexOf("example.com", StringComparison.OrdinalIgnoreCase) >= 0;

            // LAN fields
            bool lanEnabled = GetBool(d, "lanEnabled");
            bool lanRunning = GetBool(d, "lanRunning");
            int lanPort = GetInt(d, "lanPort", 18791);
            int lanDiscoveryPort = GetInt(d, "lanDiscoveryPort", 18792);
            string lanPrimaryIp = GetStr(d, "lanPrimaryIp");
            string lanBindError = GetStr(d, "lanBindError");
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

            _lanCurrentlyEnabled = lanEnabled;
            _currentWanPairCode = wanPairCode;
            _currentLanPairCode = lanPairCode;
            _currentLanIp = lanPrimaryIp;

            if (_lastRelayLoaded != relayUrl && !string.IsNullOrEmpty(relayUrl) && !_relayUrlBox.IsFocused)
            {
                _lastRelayLoaded = relayUrl;
                _relayUrlBox.Text = relayUrl;
                _chkTrustSelfSigned.IsChecked = trustSelfSigned;
            }

            // ── 0. Top Dual-Channel Overview Banner ──
            bool bothOpen = lanRunning && (!wanManualPaused || wanUnconfigured);
            _btnRestoreDual.Visibility = bothOpen ? Visibility.Collapsed : Visibility.Visible;

            if (lanPeerOnline)
            {
                _heroDot.Fill = BrushHex("#22C55E");
                _heroBannerBorder.BorderBrush = BrushHex("#2DD4BF");
                string rttNote = lanRtt >= 0 ? (" · 延迟 " + lanRtt + " ms") : "";
                _heroTitle.Text = "手机当前走【⚡ 内网直连】在线 · " + (string.IsNullOrEmpty(lanPairedName) ? "局域网手机" : lanPairedName) + rttNote;
                _heroSubtitle.Text = "电脑双通道同时常开待命：在家用内网极速直连 (" + lanPhoneIp + ")，出门在手机端直接切「外网中继」即可无缝接管，电脑无需任何操作";
                _trayIcon.Text = "ClawBridge · 手机内网直连在线";
            }
            else if (wanPeerOnline && wanPaired)
            {
                _heroDot.Fill = BrushHex("#22C55E");
                _heroBannerBorder.BorderBrush = BrushHex("#60A5FA");
                string rttNote = wanRtt >= 0 ? (" · 延迟 " + wanRtt + " ms") : "";
                _heroTitle.Text = "手机当前走【🌐 外网中继】在线 · " + (string.IsNullOrEmpty(wanPairedName) ? "远程手机" : wanPairedName) + rttNote;
                _heroSubtitle.Text = "电脑双通道同时常开待命：回到同一 WiFi 后在手机端点击「内网直连」即可切换至局域网毫秒级通道，电脑无需任何操作";
                _trayIcon.Text = "ClawBridge · 手机外网中继在线";
            }
            else if (lanRunning && wanConnected && wanAuthenticated)
            {
                _heroDot.Fill = BrushHex("#38BDF8");
                _heroBannerBorder.BorderBrush = BrushHex("#38BDF8");
                _heroTitle.Text = "⚡ 内网直连 + 🌐 外网中继 双通道已同时开启待命";
                _heroSubtitle.Text = "监听内网 " + (string.IsNullOrEmpty(lanPrimaryIp) ? "0.0.0.0" : lanPrimaryIp) + ":" + lanPort + " 与外网中继；手机端随时任选内网或外网访问";
                _trayIcon.Text = "ClawBridge · 双通道同时待命";
            }
            else if (lanRunning)
            {
                _heroDot.Fill = BrushHex("#38BDF8");
                _heroBannerBorder.BorderBrush = BrushHex("#2DD4BF");
                _heroTitle.Text = "⚡ 内网直连监听就绪（" + (wanUnconfigured ? "外网中继未配置，纯内网独立待命" : "外网中继连接中/已暂停") + "）";
                _heroSubtitle.Text = "同一 WiFi 下手机打开 App 切至「内网直连」即可连接本机 " + lanPrimaryIp + ":" + lanPort;
                _trayIcon.Text = "ClawBridge · 内网直连待命";
            }
            else
            {
                _heroDot.Fill = BrushHex("#F59E0B");
                _heroBannerBorder.BorderBrush = BrushHex("#F59E0B");
                _heroTitle.Text = "部分监听通道已暂停";
                _heroSubtitle.Text = "点击右侧「⚡+🌐 恢复双通道全开」即可同时开启内网直连与外网中继监听";
                _trayIcon.Text = "ClawBridge · 部分通道已暂停";
            }

            // ── 1A. Left Card: LAN Direct Status ──
            _lanRttBadge.Text = (lanRunning && lanPeerOnline && lanRtt >= 0) ? (lanRtt + " ms") : "-- ms";
            _lanIpText.Text = string.IsNullOrEmpty(allIpsText) ? "本机局域网 IP：未检测到局域网网卡" : ("本机局域网 IP：" + allIpsText);
            _lanPortText.Text = string.Format("WebSocket 监听：{0}  ·  UDP 自动发现：{1}", lanPort, lanDiscoveryPort);
            _btnCopyLanIp.Visibility = string.IsNullOrEmpty(lanPrimaryIp) ? Visibility.Collapsed : Visibility.Visible;
            _btnToggleLan.Content = lanEnabled ? "暂停内网监听" : "▶ 开启内网监听";
            _trayLanToggleItem.Text = lanEnabled ? "暂停内网直连监听 (18791)" : "开启内网直连监听 (18791)";

            if (!string.IsNullOrEmpty(lanBindError))
            {
                _lanDot.Fill = BrushHex("#EF4444");
                _lanCardBorder.BorderBrush = BrushHex("#EF4444");
                _lanStateTitle.Text = "⚡ 内网直连 · 端口异常";
                _lanAlertText.Text = "⚠️ " + lanBindError;
                _lanAlertText.Visibility = Visibility.Visible;
            }
            else if (lanRunning && lanPeerOnline)
            {
                _lanDot.Fill = BrushHex("#22C55E");
                _lanCardBorder.BorderBrush = BrushHex("#2DD4BF");
                _lanStateTitle.Text = "⚡ 内网直连 · 🟢 手机正在访问";
                _lanAlertText.Visibility = Visibility.Collapsed;
            }
            else if (lanRunning)
            {
                _lanDot.Fill = BrushHex("#38BDF8");
                _lanCardBorder.BorderBrush = BrushHex("#1E3A5F");
                _lanStateTitle.Text = "⚡ 内网直连 · 监听待命中";
                _lanAlertText.Visibility = Visibility.Collapsed;
            }
            else
            {
                _lanDot.Fill = BrushHex("#64748B");
                _lanCardBorder.BorderBrush = BrushHex("#1E293B");
                _lanStateTitle.Text = "⚡ 内网直连 · 已暂停";
                _lanAlertText.Visibility = Visibility.Collapsed;
            }

            bool hasLanCode = !string.IsNullOrEmpty(lanPairCode) && lanPairCode.Length == 6;
            if (hasLanCode)
            {
                _lanPairBig.FontSize = 25;
                _lanPairBig.Foreground = BrushHex("#34D399");
                _lanPairBig.Text = lanPairCode.Substring(0, 3) + " " + lanPairCode.Substring(3, 3);
                long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                long remSec = Math.Max(0, (lanPairExp - nowMs) / 1000);
                _lanPairSub.Text = string.Format("内网 6 位配对码有效倒计时：{0:D2}:{1:D2}", remSec / 60, remSec % 60);
                _btnCopyLanCode.Visibility = Visibility.Visible;
            }
            else if (lanPaired)
            {
                _lanPairBig.FontSize = 18;
                _lanPairBig.Foreground = BrushHex("#22C55E");
                _lanPairBig.Text = "已配对：" + (string.IsNullOrEmpty(lanPairedName) ? "内网手机" : lanPairedName);
                _lanPairSub.Text = lanPeerOnline
                    ? ("🟢 正在通过局域网直连通信 (" + lanPhoneIp + ")")
                    : "🔵 已绑定内网凭证，手机切至「内网直连」即刻秒连";
                _btnCopyLanCode.Visibility = Visibility.Collapsed;
            }
            else
            {
                _lanPairBig.FontSize = 20;
                _lanPairBig.Foreground = BrushHex("#94A3B8");
                _lanPairBig.Text = lanRunning ? "生成中..." : "监听未开启";
                _lanPairSub.Text = lanRunning ? "正在生成 6 位内网配对码..." : "开启内网监听后自动生成配对码";
                _btnCopyLanCode.Visibility = Visibility.Collapsed;
            }
            _btnRefreshLanCode.Visibility = lanRunning ? Visibility.Visible : Visibility.Collapsed;
            _btnUnpairLan.Visibility = lanPaired ? Visibility.Visible : Visibility.Collapsed;

            // ── 1B. Right Card: WAN Relay Status ──
            _wanRttBadge.Text = (wanConnected && wanRtt >= 0) ? (wanRtt + " ms") : "-- ms";
            _wanEndpointText.Text = wanUnconfigured ? "中继地址：未配置（仅用内网无需填写）" : ("中继：" + relayUrl);

            if (wanUnconfigured)
            {
                _wanDot.Fill = BrushHex("#64748B");
                _wanCardBorder.BorderBrush = BrushHex("#1E293B");
                _wanStateTitle.Text = "🌐 外网中继 · 未配置";
                _wanStateSub.Text = "如需 5G 跨网远程控制，请在下方填入 VPS 公网 IP:端口";
                _btnWanConnect.IsEnabled = false;
                _btnWanDisconnect.IsEnabled = false;
                _trayWanConnectItem.Enabled = false;
                _trayWanDisconnectItem.Enabled = false;
            }
            else if (wanManualPaused)
            {
                _wanDot.Fill = BrushHex("#64748B");
                _wanCardBorder.BorderBrush = BrushHex("#1E293B");
                _wanStateTitle.Text = "🌐 外网中继 · 已手动暂停";
                _wanStateSub.Text = "点击「▶ 连接外网」即可恢复外网待命";
                _btnWanConnect.IsEnabled = true;
                _btnWanDisconnect.IsEnabled = false;
                _trayWanConnectItem.Enabled = true;
                _trayWanDisconnectItem.Enabled = false;
            }
            else if (wanConnected && wanAuthenticated)
            {
                if (wanPaired && wanPeerOnline && !lanPeerOnline)
                {
                    _wanDot.Fill = BrushHex("#22C55E");
                    _wanCardBorder.BorderBrush = BrushHex("#60A5FA");
                    _wanStateTitle.Text = "🌐 外网中继 · 🟢 手机正在访问";
                    _wanStateSub.Text = "手机当前正通过外网加密通道实时连接";
                }
                else
                {
                    _wanDot.Fill = BrushHex("#38BDF8");
                    _wanCardBorder.BorderBrush = BrushHex("#1E3A5F");
                    _wanStateTitle.Text = "🌐 外网中继 · 已连接待命中";
                    _wanStateSub.Text = lanPeerOnline
                        ? "外网通道保持后台待命（当前手机正在走内网直连）"
                        : "中继已就绪，手机在外面切至「外网中继」即可随时接入";
                }
                _btnWanConnect.IsEnabled = false;
                _btnWanDisconnect.IsEnabled = true;
                _trayWanConnectItem.Enabled = false;
                _trayWanDisconnectItem.Enabled = true;
            }
            else
            {
                _wanDot.Fill = BrushHex("#F59E0B");
                _wanCardBorder.BorderBrush = BrushHex("#1E293B");
                _wanStateTitle.Text = "🌐 外网中继 · 正在重连...";
                _wanStateSub.Text = "正在连接 " + relayUrl;
                _btnWanConnect.IsEnabled = false;
                _btnWanDisconnect.IsEnabled = true;
                _trayWanConnectItem.Enabled = false;
                _trayWanDisconnectItem.Enabled = true;
            }

            if (wanPaired)
            {
                _wanPairBig.FontSize = 18;
                _wanPairBig.Foreground = BrushHex("#22C55E");
                _wanPairBig.Text = "已配对：" + (string.IsNullOrEmpty(wanPairedName) ? "外网手机" : wanPairedName);
                _wanPairSub.Text = (wanPeerOnline && !lanPeerOnline)
                    ? "🟢 正在通过外网中继实时同步"
                    : "🔵 已绑定外网凭证，手机切至「外网中继」即刻秒连";
                _btnCopyWanCode.Visibility = Visibility.Collapsed;
            }
            else if (!string.IsNullOrEmpty(wanPairCode) && wanPairCode.Length == 6)
            {
                _wanPairBig.FontSize = 25;
                _wanPairBig.Foreground = BrushHex("#60A5FA");
                _wanPairBig.Text = wanPairCode.Substring(0, 3) + " " + wanPairCode.Substring(3, 3);
                long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                long remSec = Math.Max(0, (wanPairExp - nowMs) / 1000);
                _wanPairSub.Text = string.Format("外网 6 位配对码有效倒计时：{0:D2}:{1:D2}", remSec / 60, remSec % 60);
                _btnCopyWanCode.Visibility = Visibility.Visible;
            }
            else
            {
                _wanPairBig.FontSize = 20;
                _wanPairBig.Foreground = BrushHex("#94A3B8");
                _wanPairBig.Text = wanUnconfigured ? "待填地址" : (wanManualPaused ? "已暂停" : "待签发...");
                _wanPairSub.Text = wanUnconfigured ? "填写下方 VPS 地址后自动获取外网配对码" : "连接外网中继后自动签发 6 位配对码";
                _btnCopyWanCode.Visibility = Visibility.Collapsed;
            }

            // ── 2. Quota Card ──
            if (d.ContainsKey("quota") && d["quota"] is Dictionary<string, object>)
            {
                var q = (Dictionary<string, object>)d["quota"];
                _quotaPlanText.Text = "反重力套餐：" + GetStr(q, "planName");
                _quotaGeminiText.Text = string.Format("AGY Gemini 5h:{0}% | 7d:{1}%",
                    GetInt(q, "gemini5hRem", 0), GetInt(q, "gemini7dRem", 0));
                _quotaClaudeText.Text = string.Format("AGY 附赠池 5h:{0}% | 7d:{1}%",
                    GetInt(q, "claude5hRem", 0), GetInt(q, "claude7dRem", 0));
            }
            if (d.ContainsKey("codexQuota") && d["codexQuota"] is Dictionary<string, object>)
            {
                var cq = (Dictionary<string, object>)d["codexQuota"];
                _quotaCodexText.Text = string.Format("Codex({0}) 5h:{1}% | 7d:{2}%",
                    GetStr(cq, "planName"), GetInt(cq, "window5hRem", 0), GetInt(cq, "window7dRem", 0));
            }
            string cdpNote = GetStr(d, "cdpNote");
            _cdpText.Text = !string.IsNullOrEmpty(cdpNote)
                ? ("本机通道：🟢 " + cdpNote)
                : "本机通道：🟢 Transcript 实时镜像运行中";

            // ── 4. Logs ──
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

        private static bool IsBareIpv4RelayUrl(string url)
        {
            try
            {
                var uri = new Uri(url);
                IPAddress ip;
                if (IPAddress.TryParse(uri.Host, out ip) && ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                {
                    return uri.Host != "127.0.0.1";
                }
            }
            catch { }
            return false;
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
