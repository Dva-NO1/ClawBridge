# Build standalone Windows one-click installer EXE (ClawBridge-v<Version>-windows-setup.exe)
# Bundles compiled ClawBridgeDesktop.exe + bridge/src + production node_modules + icon + persona.md
# Requires only Windows built-in .NET Framework 4.x csc.exe and Node.js.
param(
  [string]$Version = "1.0.0",
  [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$BridgeDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$RepoDir = (Resolve-Path (Join-Path $BridgeDir "..")).Path
if ([string]::IsNullOrWhiteSpace($OutDir)) {
  $OutDir = Join-Path $RepoDir "dist"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$Csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $Csc)) {
  $Csc = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path $Csc)) {
  throw "Could not find .NET Framework 4.x csc.exe"
}

Write-Host "[1/4] Installing production bridge dependencies..."
Push-Location $BridgeDir
npm install --omit=dev --no-audit --no-fund | Out-Null
Pop-Location

Write-Host "[2/4] Compiling ClawBridgeDesktop.exe (without touching Desktop shortcuts)..."
$DesktopCs = Join-Path $BridgeDir "desktop-gui\ClawBridgeDesktop.cs"
$DesktopExe = Join-Path $BridgeDir "ClawBridgeDesktop.exe"
$IconPath = Join-Path $BridgeDir "clawbridge.ico"
$WpfDir = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\WPF"
if (-not (Test-Path $WpfDir)) {
  $WpfDir = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\WPF"
}
$guiArgs = @(
  "/nologo", "/codepage:65001", "/target:winexe", "/optimize+", "/out:$DesktopExe",
  "/r:System.dll", "/r:System.Core.dll", "/r:System.Xaml.dll",
  "/r:System.Drawing.dll", "/r:System.Windows.Forms.dll",
  "/r:System.Web.Extensions.dll", "/r:System.Net.Http.dll",
  "/r:$WpfDir\WindowsBase.dll", "/r:$WpfDir\PresentationCore.dll", "/r:$WpfDir\PresentationFramework.dll"
)
if (Test-Path $IconPath) { $guiArgs += "/win32icon:$IconPath" }
$guiArgs += $DesktopCs
& $Csc @guiArgs
if ($LASTEXITCODE -ne 0) { throw "Failed compiling ClawBridgeDesktop.exe" }

Write-Host "[3/4] Packaging clean payload.zip..."
$StageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("clawbridge-setup-stage-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
$StageBridge = Join-Path $StageRoot "bridge"
New-Item -ItemType Directory -Force -Path "$StageBridge\src", "$StageBridge\scripts" | Out-Null

Copy-Item "$BridgeDir\ClawBridgeDesktop.exe", "$BridgeDir\clawbridge.ico", "$BridgeDir\package.json", "$BridgeDir\config.example.json" $StageBridge
Copy-Item "$BridgeDir\src\*.mjs" "$StageBridge\src\"
Copy-Item "$BridgeDir\scripts\start-antigravity-cdp.ps1", "$BridgeDir\scripts\cdp-probe.mjs" "$StageBridge\scripts\"
Copy-Item -Recurse "$BridgeDir\node_modules" "$StageBridge\node_modules"
if (Test-Path "$RepoDir\persona.md") { Copy-Item "$RepoDir\persona.md" "$StageRoot\persona.md" }
if (Test-Path "$RepoDir\LICENSE") { Copy-Item "$RepoDir\LICENSE" "$StageRoot\LICENSE" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$ZipTemp = Join-Path ([System.IO.Path]::GetTempPath()) ("clawbridge-payload-" + [Guid]::NewGuid().ToString("N").Substring(0, 8) + ".zip")
[System.IO.Compression.ZipFile]::CreateFromDirectory($StageRoot, $ZipTemp, [System.IO.Compression.CompressionLevel]::Optimal, $false)

Write-Host "[4/4] Compiling standalone installer EXE..."
$SetupCs = Join-Path $BridgeDir "desktop-gui\ClawBridgeSetup.cs"
$SetupExe = Join-Path $OutDir "ClawBridge-v$Version-windows-setup.exe"
$setupArgs = @(
  "/nologo", "/codepage:65001", "/target:winexe", "/optimize+", "/out:$SetupExe",
  "/r:System.dll", "/r:System.Core.dll", "/r:System.Drawing.dll",
  "/r:System.Windows.Forms.dll", "/r:System.IO.Compression.dll", "/r:System.IO.Compression.FileSystem.dll",
  "/res:$ZipTemp,payload.zip"
)
if (Test-Path $IconPath) { $setupArgs += "/win32icon:$IconPath" }
$setupArgs += $SetupCs
& $Csc @setupArgs
if ($LASTEXITCODE -ne 0) { throw "Failed compiling ClawBridgeSetup.exe" }

Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $StageRoot, $ZipTemp
Write-Host "Standalone Windows installer ready: $SetupExe ($((Get-Item $SetupExe).Length) bytes)"
