@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ========================================
echo  Use local MP4 for anomaly offline test
echo  (Java StreamManager file-loop, no camera)
echo ========================================
echo.

set "VIDEO=%~1"
if "%VIDEO%"=="" set "VIDEO=%~dp0testdata\debris-place-test.mp4"

if not exist "%VIDEO%" (
  echo Video not found: %VIDEO%
  echo.
  echo Usage: use-local-video.bat "D:\path\to\video.mp4"
  echo Or put file at testdata\debris-place-test.mp4
  pause
  exit /b 1
)

REM Write absolute path into application.yml stream.file-path
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p='%~dp0server\src\main\resources\application.yml';" ^
  "$abs=(Resolve-Path -LiteralPath '%VIDEO%').Path -replace '\\','/';" ^
  "$c=Get-Content -LiteralPath $p -Raw -Encoding UTF8;" ^
  "if ($c -match '(?m)^  file-path:\s*.*$') {" ^
  "  $c=[regex]::Replace($c,'(?m)^  file-path:\s*.*$','  file-path: \"'+$abs+'\"',1)" ^
  "} else {" ^
  "  $c=[regex]::Replace($c,'(?m)^(  rtsp-url:.*)$','$1'+[Environment]::NewLine+'  file-path: \"'+$abs+'\"',1)" ^
  "};" ^
  "[IO.File]::WriteAllText($p,$c,(New-Object System.Text.UTF8Encoding $false));" ^
  "Write-Host ('file-path -> '+$abs)"

echo.
echo Next:
echo  1. Restart Spring Boot: server\start-server.bat
echo  2. Open road monitor, enable 道路异常检测
echo  3. Video loops automatically; bg learns on first ~3s of each loop start after mode on
echo.
echo NOTE: Frontend HLS preview may stay offline without MediaMTX;
echo       anomaly ONNX still runs on the local file frames.
echo.
echo Restore camera: clear-local-video.bat
pause
