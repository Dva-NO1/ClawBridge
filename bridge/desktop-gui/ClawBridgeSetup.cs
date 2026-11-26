using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Windows.Forms;

namespace ClawBridgeSetup
{
    static class Program
    {
        private const string AppVersion = "2.0.1";

        [STAThread]
        static int Main(string[] args)
        {
            // Headless extraction verification mode (for CI / automated testing):
            // ClawBridge-v1.0.0-windows-setup.exe --extract-only "<targetDir>"
            if (args.Length >= 2 && string.Equals(args[0], "--extract-only", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    ExtractPayloadTo(args[1]);
                    return 0;
                }
                catch (Exception ex)
                {
                    Console.Error.WriteLine("Extract failed: " + ex.Message);
                    return 1;
                }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallerForm());
            return 0;
        }

        internal static void ExtractPayloadTo(string targetDir)
        {
            Directory.CreateDirectory(targetDir);
            var asm = Assembly.GetExecutingAssembly();
            using (var stream = asm.GetManifestResourceStream("payload.zip"))
            {
                if (stream == null)
                    throw new InvalidOperationException("Embedded payload.zip resource not found.");

                using (var archive = new ZipArchive(stream, ZipArchiveMode.Read))
                {
                    foreach (var entry in archive.Entries)
                    {
                        string relPath = entry.FullName.Replace('/', Path.DirectorySeparatorChar);
                        if (string.IsNullOrEmpty(relPath)) continue;
                        string destPath = Path.GetFullPath(Path.Combine(targetDir, relPath));
                        string rootFull = Path.GetFullPath(targetDir).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
                        if (!destPath.StartsWith(rootFull, StringComparison.OrdinalIgnoreCase))
                            throw new InvalidOperationException("Invalid zip entry path: " + entry.FullName);

                        if (entry.FullName.EndsWith("/") || entry.FullName.EndsWith("\\"))
                        {
                            Directory.CreateDirectory(destPath);
                            continue;
                        }
                        Directory.CreateDirectory(Path.GetDirectoryName(destPath));
                        using (var inStream = entry.Open())
                        using (var outStream = new FileStream(destPath, FileMode.Create, FileAccess.Write, FileShare.None))
                        {
                            inStream.CopyTo(outStream);
                        }
                    }
                }
            }
        }

        internal static bool HasNodeJs()
        {
            try
            {
                var psi = new ProcessStartInfo("node.exe", "--version")
                {
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    CreateNoWindow = true
                };
                using (var p = Process.Start(psi))
                {
                    if (p == null) return false;
                    p.WaitForExit(3000);
                    return p.ExitCode == 0;
                }
            }
            catch
            {
                return false;
            }
        }

        internal static void CreateDesktopShortcut(string installDir)
        {
            string exePath = Path.Combine(installDir, "bridge", "ClawBridgeDesktop.exe");
            string icoPath = Path.Combine(installDir, "bridge", "clawbridge.ico");
            string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            string lnkPath = Path.Combine(desktop, "ClawBridge 桌面控制台.lnk");

            string ps =
                "$ws = New-Object -ComObject WScript.Shell; " +
                "$s = $ws.CreateShortcut('" + EscapePs(lnkPath) + "'); " +
                "$s.TargetPath = '" + EscapePs(exePath) + "'; " +
                "$s.WorkingDirectory = '" + EscapePs(Path.Combine(installDir, "bridge")) + "'; " +
                "$s.IconLocation = '" + EscapePs(icoPath) + ",0'; " +
                "$s.Description = 'ClawBridge Desktop Bridge Console'; " +
                "$s.Save();";
            RunPowerShellHidden(ps);
        }

        internal static void RegisterAutoStartTask(string installDir)
        {
            string exePath = Path.Combine(installDir, "bridge", "ClawBridgeDesktop.exe");
            string workDir = Path.Combine(installDir, "bridge");
            string ps =
                "$a = New-ScheduledTaskAction -Execute '" + EscapePs(exePath) + "' -Argument '--minimized' -WorkingDirectory '" + EscapePs(workDir) + "'; " +
                "$t = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME; " +
                "$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew; " +
                "Register-ScheduledTask -TaskName 'ClawBridge' -Action $a -Trigger $t -Settings $s -Force | Out-Null;";
            RunPowerShellHidden(ps);
        }

        internal static void StopExistingBridge(string installDir)
        {
            string escapedDir = EscapePs(installDir);
            string ps =
                "Get-Process -Name ClawBridgeDesktop -ErrorAction SilentlyContinue | Stop-Process -Force; " +
                "Get-CimInstance Win32_Process -Filter \"Name='node.exe'\" -ErrorAction SilentlyContinue | " +
                "Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf('" + escapedDir + "', [StringComparison]::OrdinalIgnoreCase) -ge 0 } | " +
                "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; " +
                "Start-Sleep -Milliseconds 300;";
            RunPowerShellHidden(ps);
        }

        private static string EscapePs(string s)
        {
            return (s ?? "").Replace("'", "''");
        }

        private static void RunPowerShellHidden(string command)
        {
            string encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(command));
            var psi = new ProcessStartInfo("powershell.exe", "-NoProfile -ExecutionPolicy Bypass -EncodedCommand " + encoded)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using (var p = Process.Start(psi))
            {
                if (p != null) p.WaitForExit(15000);
            }
        }

        private sealed class InstallerForm : Form
        {
            private readonly TextBox _dirBox;
            private readonly CheckBox _chkShortcut;
            private readonly CheckBox _chkAutoStart;
            private readonly CheckBox _chkLaunchNow;
            private readonly Label _statusLabel;
            private readonly ProgressBar _progress;
            private readonly Button _btnInstall;

            public InstallerForm()
            {
                Text = "ClawBridge Desktop 安装程序 v" + AppVersion;
                ClientSize = new Size(520, 340);
                FormBorderStyle = FormBorderStyle.FixedDialog;
                MaximizeBox = false;
                StartPosition = FormStartPosition.CenterScreen;
                BackColor = Color.FromArgb(20, 20, 20);
                ForeColor = Color.FromArgb(236, 236, 236);
                Font = new Font("Microsoft YaHei UI", 9.5f);

                try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

                var title = new Label
                {
                    Text = "安装 ClawBridge 桌面桥 (v" + AppVersion + ")",
                    Font = new Font("Microsoft YaHei UI", 14f, FontStyle.Bold),
                    ForeColor = Color.FromArgb(236, 236, 236),
                    Location = new Point(24, 20),
                    AutoSize = true
                };
                Controls.Add(title);

                var sub = new Label
                {
                    Text = "包含零黑窗托盘控制台 (ClawBridgeDesktop.exe) 与双模式桥接引擎。\n支持同一 WiFi「内网直连」免服务器极速通信，或填写自建 VPS 中继跨网远程管理。",
                    ForeColor = Color.FromArgb(155, 155, 155),
                    Location = new Point(24, 52),
                    Size = new Size(472, 42)
                };
                Controls.Add(sub);

                var dirLbl = new Label
                {
                    Text = "安装目录：",
                    ForeColor = Color.FromArgb(200, 200, 200),
                    Location = new Point(24, 104),
                    AutoSize = true
                };
                Controls.Add(dirLbl);

                string defaultDir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "ClawBridge"
                );
                _dirBox = new TextBox
                {
                    Text = defaultDir,
                    Location = new Point(24, 126),
                    Size = new Size(392, 26),
                    BackColor = Color.FromArgb(30, 30, 30),
                    ForeColor = Color.FromArgb(236, 236, 236),
                    BorderStyle = BorderStyle.FixedSingle
                };
                Controls.Add(_dirBox);

                var btnBrowse = new Button
                {
                    Text = "浏览...",
                    Location = new Point(422, 125),
                    Size = new Size(74, 27),
                    FlatStyle = FlatStyle.Flat,
                    BackColor = Color.FromArgb(40, 40, 40),
                    ForeColor = Color.FromArgb(236, 236, 236)
                };
                btnBrowse.FlatAppearance.BorderColor = Color.FromArgb(66, 66, 66);
                btnBrowse.Click += (s, e) =>
                {
                    using (var fbd = new FolderBrowserDialog { SelectedPath = _dirBox.Text })
                    {
                        if (fbd.ShowDialog(this) == DialogResult.OK)
                            _dirBox.Text = fbd.SelectedPath;
                    }
                };
                Controls.Add(btnBrowse);

                _chkShortcut = new CheckBox
                {
                    Text = "创建桌面快捷方式（ClawBridge 桌面控制台）",
                    Checked = true,
                    Location = new Point(24, 166),
                    AutoSize = true
                };
                Controls.Add(_chkShortcut);

                _chkAutoStart = new CheckBox
                {
                    Text = "注册 Windows 登录自启（后台静默托盘常驻，无黑窗）",
                    Checked = true,
                    Location = new Point(24, 192),
                    AutoSize = true
                };
                Controls.Add(_chkAutoStart);

                _chkLaunchNow = new CheckBox
                {
                    Text = "安装完成后立即打开 ClawBridge 桌面控制台",
                    Checked = true,
                    Location = new Point(24, 218),
                    AutoSize = true
                };
                Controls.Add(_chkLaunchNow);

                _progress = new ProgressBar
                {
                    Location = new Point(24, 254),
                    Size = new Size(472, 10),
                    Style = ProgressBarStyle.Continuous
                };
                Controls.Add(_progress);

                _statusLabel = new Label
                {
                    Text = HasNodeJs()
                        ? "已检测到 Node.js 环境，点击「立即安装」开始。"
                        : "提示：未检测到 node.exe，安装后需安装 Node.js >= 22 (https://nodejs.org)。",
                    ForeColor = HasNodeJs() ? Color.FromArgb(74, 222, 128) : Color.FromArgb(240, 185, 65),
                    Location = new Point(24, 272),
                    Size = new Size(340, 40)
                };
                Controls.Add(_statusLabel);

                _btnInstall = new Button
                {
                    Text = "立即安装",
                    Location = new Point(386, 276),
                    Size = new Size(110, 36),
                    FlatStyle = FlatStyle.Flat,
                    BackColor = Color.FromArgb(236, 236, 236),
                    ForeColor = Color.FromArgb(20, 20, 20),
                    Font = new Font("Microsoft YaHei UI", 9.5f, FontStyle.Bold)
                };
                _btnInstall.FlatAppearance.BorderSize = 0;
                _btnInstall.Click += OnInstallClick;
                Controls.Add(_btnInstall);
            }

            private void OnInstallClick(object sender, EventArgs e)
            {
                string installDir = (_dirBox.Text ?? "").Trim();
                if (string.IsNullOrEmpty(installDir))
                {
                    MessageBox.Show(this, "请选择有效的安装目录。", "提示", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }

                _btnInstall.Enabled = false;
                try
                {
                    _statusLabel.ForeColor = Color.FromArgb(155, 155, 155);
                    _statusLabel.Text = "正在停止旧实例并解压文件...";
                    _progress.Value = 25;
                    Application.DoEvents();

                    StopExistingBridge(installDir);
                    ExtractPayloadTo(installDir);

                    _progress.Value = 65;
                    if (_chkShortcut.Checked)
                    {
                        _statusLabel.Text = "正在创建桌面快捷方式...";
                        Application.DoEvents();
                        CreateDesktopShortcut(installDir);
                    }

                    _progress.Value = 85;
                    if (_chkAutoStart.Checked)
                    {
                        _statusLabel.Text = "正在注册开机登录静默自启...";
                        Application.DoEvents();
                        RegisterAutoStartTask(installDir);
                    }

                    _progress.Value = 100;
                    _statusLabel.ForeColor = Color.FromArgb(74, 222, 128);
                    _statusLabel.Text = "安装完成！安装路径：" + installDir;

                    if (_chkLaunchNow.Checked)
                    {
                        string guiExe = Path.Combine(installDir, "bridge", "ClawBridgeDesktop.exe");
                        if (File.Exists(guiExe))
                        {
                            Process.Start(new ProcessStartInfo(guiExe)
                            {
                                WorkingDirectory = Path.Combine(installDir, "bridge"),
                                UseShellExecute = true
                            });
                        }
                    }

                    MessageBox.Show(
                        this,
                        "ClawBridge 桌面桥已成功安装到：\n" + installDir + "\n\n首次使用请在控制台填写你的自建 VPS 中继地址并保存。",
                        "安装完成",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Information
                    );
                    Close();
                }
                catch (Exception ex)
                {
                    _btnInstall.Enabled = true;
                    _statusLabel.ForeColor = Color.FromArgb(240, 113, 120);
                    _statusLabel.Text = "安装失败：" + ex.Message;
                    MessageBox.Show(this, "安装失败：\n" + ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
        }
    }
}
