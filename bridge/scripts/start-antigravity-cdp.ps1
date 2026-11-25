# 以远程调试端口 9222 拉起或提示重启接管 Antigravity (Spec 3.2)
param(
  [switch]$ForceRestart
)

$ExePath = if ($env:ANTIGRAVITY_EXE_PATH) { $env:ANTIGRAVITY_EXE_PATH } else { Join-Path $env:LOCALAPPDATA "Programs\antigravity\Antigravity.exe" }
$CdpPort = 9222

try {
  $ver = Invoke-RestMethod -Uri "http://127.0.0.1:$CdpPort/json/version" -TimeoutSec 2 -ErrorAction Stop
  Write-Host "[OK] Antigravity 已在调试端口 $CdpPort 运行 ($($ver.Browser))，无需重启。" -ForegroundColor Green
  exit 0
} catch {}

$running = Get-Process -Name "Antigravity" -ErrorAction SilentlyContinue
if ($running -and -not $ForceRestart) {
  Write-Host "[提示] 检测到 Antigravity 已在运行，但未开启 --remote-debugging-port=$CdpPort。" -ForegroundColor Yellow
  Write-Host "请保存当前工作后，带 -ForceRestart 参数重新执行本脚本完成重启接管："
  Write-Host "  powershell -ExecutionPolicy Bypass -File .\bridge\scripts\start-antigravity-cdp.ps1 -ForceRestart"
  exit 2
}

if ($running -and $ForceRestart) {
  Write-Host "正在关闭现有 Antigravity 实例并以调试端口 $CdpPort 重新拉起..."
  Stop-Process -Name "Antigravity" -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
}

Start-Process -FilePath $ExePath -ArgumentList "--remote-debugging-port=$CdpPort"
Write-Host "[OK] 已拉起 Antigravity (--remote-debugging-port=$CdpPort)。" -ForegroundColor Green
