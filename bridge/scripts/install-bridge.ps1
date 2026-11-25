# ClawBridge 桌面桥安装与开机自启注册脚本
# 用法: powershell -ExecutionPolicy Bypass -File .\bridge\scripts\install-bridge.ps1

$ErrorActionPreference = "Stop"
$BridgeDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$NodeExe = (Get-Command node.exe).Source
$EntryScript = Join-Path $BridgeDir "src\index.mjs"
$AppDataDir = Join-Path $env:APPDATA "clawbridge"

Write-Host "[1/3] Installing Bridge npm dependencies..."
Push-Location $BridgeDir
npm install --omit=dev --no-audit --no-fund
Pop-Location

Write-Host "[2/3] Initializing %APPDATA%\clawbridge\config.json..."
New-Item -ItemType Directory -Force -Path $AppDataDir | Out-Null
& $NodeExe -e "import('./bridge/src/claw-config.mjs').then(m => { const c = m.loadConfig(); console.log('Config initialized at', m.getConfigPath(), 'relayUrl=', c.relayUrl); })"

Write-Host "[3/4] Building ClawBridgeDesktop.exe GUI (zero-console winexe tray host)..."
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build-desktop-gui.ps1")
$GuiExe = Join-Path $BridgeDir "ClawBridgeDesktop.exe"

Write-Host "[4/4] Registering Windows Scheduled Task 'ClawBridge' (Silent Tray Auto-Start + Zero Console Window)..."
$TaskName = "ClawBridge"
$Action = New-ScheduledTaskAction -Execute $GuiExe -Argument "--minimized" -WorkingDirectory $BridgeDir
$Trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$Settings = New-ScheduledTaskSettingsSet `
  -AllowStartIfOnBatteries `
  -DontStopIfGoingOnBatteries `
  -StartWhenAvailable `
  -ExecutionTimeLimit ([TimeSpan]::Zero) `
  -RestartCount 999 `
  -RestartInterval (New-TimeSpan -Minutes 1) `
  -MultipleInstances IgnoreNew

Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Settings $Settings -Force | Out-Null
# Clean up legacy Run key to avoid duplicate launch
Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "ClawBridgeDesktop" -ErrorAction SilentlyContinue
Write-Host "Scheduled Task '$TaskName' registered (launches ClawBridgeDesktop.exe --minimized silently, zero console window)."
