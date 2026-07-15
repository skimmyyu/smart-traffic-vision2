$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$yolo = Join-Path $root 'ai\yolov8'
$py = Join-Path $yolo '.venv\Scripts\python.exe'
$bat = Join-Path $yolo 'live-bridge.bat'

$v4 = Join-Path $yolo 'runs\detect\sandbox-car-v4\weights\best.pt'

Write-Host '========================================'
Write-Host ' YOLO Live Bridge launcher (v4)'
Write-Host '========================================'
Write-Host "Project: $root"

if (-not (Test-Path -LiteralPath $py)) {
  Write-Host "[ERROR] YOLO venv python not found:"
  Write-Host "  $py"
  Write-Host "Run setup-yolo-venv.ps1 first."
  exit 1
}
if (-not (Test-Path -LiteralPath $v4)) {
  Write-Host "[ERROR] sandbox-car-v4 weights not found:"
  Write-Host "  $v4"
  exit 1
}

# Avoid "another live_bridge is already running"
$lock = Join-Path $yolo 'runs\live_bridge.lock'
if (Test-Path -LiteralPath $lock) { Remove-Item -LiteralPath $lock -Force }
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match 'live_bridge.py' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Write-Host 'Starting YOLO-Bridge-v4 window...'
Start-Process -FilePath 'cmd.exe' -ArgumentList @('/k', 'call', "`"$bat`"") -WorkingDirectory $yolo
Write-Host 'Started sandbox-car-v4. Keep YOLO-Bridge-v4 open for detection boxes.'
Write-Host 'Monitor: http://localhost:5173/monitor'
