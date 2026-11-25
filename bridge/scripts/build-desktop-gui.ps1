# 编译 ClawBridgeDesktop.exe 原生 Windows 桌面控制端并创建桌面快捷方式
$ErrorActionPreference = "Stop"
$BridgeDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$CsFile = Join-Path $BridgeDir "desktop-gui\ClawBridgeDesktop.cs"
$OutExe = Join-Path $BridgeDir "ClawBridgeDesktop.exe"
$IcoFile = Join-Path $BridgeDir "clawbridge.ico"

$Csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$WpfDir = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\WPF"

Stop-Process -Name "ClawBridgeDesktop" -Force -ErrorAction SilentlyContinue

Write-Host "[1/2] Compiling native ClawBridgeDesktop.exe..."
& $Csc /nologo /target:winexe /optimize+ `
  "/out:$OutExe" `
  "/win32icon:$IcoFile" `
  "/r:$WpfDir\PresentationCore.dll" `
  "/r:$WpfDir\PresentationFramework.dll" `
  "/r:$WpfDir\WindowsBase.dll" `
  "/r:System.Xaml.dll" `
  "/r:System.Windows.Forms.dll" `
  "/r:System.Drawing.dll" `
  "/r:System.Web.Extensions.dll" `
  $CsFile
if ($LASTEXITCODE -ne 0) { throw "csc.exe compilation failed with exit code $LASTEXITCODE" }

Write-Host "[2/2] Creating Desktop shortcut 'ClawBridge 桌面控制台.lnk'..."
$DesktopPath = [Environment]::GetFolderPath("Desktop")
$ShortcutFile = Join-Path $DesktopPath "ClawBridge 桌面控制台.lnk"
$WshShell = New-Object -ComObject WScript.Shell
$Shortcut = $WshShell.CreateShortcut($ShortcutFile)
$Shortcut.TargetPath = $OutExe
$Shortcut.WorkingDirectory = $BridgeDir
$Shortcut.IconLocation = "$IcoFile,0"
$Shortcut.Description = "ClawBridge 桌面可视化控制台（连接/断开中继、查看配对码与配额）"
$Shortcut.Save()

Write-Host "Build complete: $OutExe"
Write-Host "Shortcut ready: $ShortcutFile"
