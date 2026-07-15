$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$yolo = Join-Path $root 'ai\yolov8'
$venv = Join-Path $yolo '.venv'
$py = Join-Path $venv 'Scripts\python.exe'
$req = Join-Path $yolo 'requirements.txt'

function Find-SystemPython {
    if ($env:PYTHON -and (Test-Path -LiteralPath $env:PYTHON)) {
        return (Resolve-Path -LiteralPath $env:PYTHON).Path
    }
    foreach ($candidate in @(
        'D:\Python312\python.exe',
        'C:\Python312\python.exe',
        'C:\Python311\python.exe'
    )) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $cmd = Get-Command py -ErrorAction SilentlyContinue
    if ($cmd) {
        $exe = & py -3 -c "import sys; print(sys.executable)" 2>$null
        if ($exe -and (Test-Path -LiteralPath $exe.Trim())) { return $exe.Trim() }
    }
    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source -and (Test-Path -LiteralPath $cmd.Source) -and ($cmd.Source -notmatch 'WindowsApps')) {
        return $cmd.Source
    }
    throw @"
System Python 3 not found.
Install Python 3.11+ or set: set PYTHON=D:\Python312\python.exe
Then re-run setup-yolo-venv.bat
"@
}

function Test-VenvOk([string]$venvPython) {
    if (-not (Test-Path -LiteralPath $venvPython)) { return $false }
    $cfg = Join-Path (Split-Path -Parent (Split-Path -Parent $venvPython)) 'pyvenv.cfg'
    if (Test-Path -LiteralPath $cfg) {
        $text = Get-Content -LiteralPath $cfg -Raw
        if ($text -match '(?m)^home\s*=\s*(.+)$') {
            $pyHome = $Matches[1].Trim().Trim('"')
            if ($pyHome -and -not (Test-Path -LiteralPath $pyHome)) { return $false }
        }
        if ($text -match 'Xiezz111') { return $false }
    }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $venvPython -c "import sys" 2>$null | Out-Null
    $ok = ($LASTEXITCODE -eq 0)
    $ErrorActionPreference = $prev
    return $ok
}

$sysPy = Find-SystemPython
Write-Host "Using system Python: $sysPy"

if ((Test-Path -LiteralPath $venv) -and -not (Test-VenvOk $py)) {
    Write-Host "Removing broken .venv (wrong machine path)..."
    Remove-Item -LiteralPath $venv -Recurse -Force
}

if (-not (Test-VenvOk $py)) {
    Write-Host "Creating venv at: $venv"
    & $sysPy -m venv $venv
    if (-not (Test-VenvOk $py)) { throw "Failed to create venv at $venv" }
}

# Clean leftover broken pip installs (Windows often leaves ~ip*)
Get-ChildItem -LiteralPath (Join-Path $venv 'Lib\site-packages') -Force -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -like '~*' -or $_.Name -like 'pip-*-tmp*' } |
  ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }

Write-Host 'Ensuring pip / setuptools / wheel...'
try {
  & $py -m ensurepip --upgrade 2>$null | Out-Null
} catch {}
& $py -m pip install -U setuptools wheel
if ($LASTEXITCODE -ne 0) {
  Write-Host 'WARN: setuptools/wheel upgrade failed, continuing...'
}

Write-Host 'Installing torch cu128 (may take several minutes)...'
& $py -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu128
if ($LASTEXITCODE -ne 0) { throw 'torch install failed' }

Write-Host 'Installing requirements...'
& $py -m pip install -r $req
if ($LASTEXITCODE -ne 0) { throw 'requirements install failed' }

Write-Host 'Smoke test...'
& $py -c "import torch, ultralytics, cv2; print('torch', torch.__version__, 'cuda', torch.cuda.is_available()); print('ultralytics', ultralytics.__version__); print('cv2', cv2.__version__)"

Write-Host 'DONE. Next: ai\yolov8\live-bridge.bat  or  start-all.bat'
