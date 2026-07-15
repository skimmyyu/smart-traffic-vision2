@echo off
setlocal EnableExtensions
chcp 65001 >nul 2>&1
cd /d "%~dp0"

echo ========================================
echo  Smart Traffic Vision - Start All
echo ========================================
echo.

if not exist "%~dp0mediamtx\mediamtx.exe" (
  echo [ERROR] mediamtx\mediamtx.exe not found
  goto END
)
if not exist "%~dp0server\pom.xml" (
  echo [ERROR] server\pom.xml not found
  goto END
)
if not exist "%~dp0web\package.json" (
  echo [ERROR] web\package.json not found
  goto END
)

echo [1/4] Starting MediaMTX...
start "MediaMTX" cmd /k "cd /d ""%~dp0mediamtx"" && call start-mediamtx.bat"
timeout /t 5 /nobreak >nul

echo [2/4] Starting Spring Boot...
start "Server" cmd /k "cd /d ""%~dp0server"" && call start-server.bat"

echo       Waiting for backend :8080 ...
set /a _wait=0
:WAIT_API
timeout /t 2 /nobreak >nul
set /a _wait+=2
powershell -NoProfile -Command "try { $r=Invoke-WebRequest -Uri http://127.0.0.1:8080/api/models/active -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -eq 200) { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>&1
if %ERRORLEVEL%==0 goto API_OK
if %_wait% GEQ 90 (
  echo [WARN] Backend not ready after 90s, still starting YOLO bridge...
  goto API_OK
)
goto WAIT_API

:API_OK
echo       Backend is ready.

echo [3/4] Starting Vue Frontend...
start "Web" cmd /k "cd /d ""%~dp0web"" && call start-web.bat"
timeout /t 2 /nobreak >nul

echo [4/4] Starting YOLO live bridge...
if not exist "%~dp0ai\yolov8\.venv\Scripts\python.exe" (
  echo [WARN] ai\yolov8\.venv not found, skip YOLO bridge
  echo        Run setup-yolo-venv.bat first, then ai\yolov8\live-bridge.bat
  goto AFTER_YOLO
)
"%~dp0ai\yolov8\.venv\Scripts\python.exe" -c "import sys" >nul 2>&1
if errorlevel 1 (
  echo [WARN] ai\yolov8\.venv is broken ^(wrong PC path^).
  echo        Run setup-yolo-venv.bat, then ai\yolov8\live-bridge.bat
  goto AFTER_YOLO
)
if exist "%~dp0ai\yolov8\runs\detect\sandbox-car-v4\weights\best.pt" goto START_YOLO
if exist "%~dp0ai\yolov8\runs\detect\sandbox-car-v3\weights\best.pt" goto START_YOLO
if exist "%~dp0ai\yolov8\weights\yolov8n.pt" goto START_YOLO
echo [WARN] No YOLO weights found, skip YOLO bridge
goto AFTER_YOLO

:START_YOLO
powershell -NoProfile -Command "$lock='%~dp0ai\yolov8\runs\live_bridge.lock'; if (Test-Path -LiteralPath $lock) { Remove-Item -LiteralPath $lock -Force }; Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'live_bridge.py' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1
start "YOLO-Bridge" /D "%~dp0ai\yolov8" cmd /k call live-bridge.bat

:AFTER_YOLO
echo.
echo Frontend: http://localhost:5173/monitor
echo Backend:  http://127.0.0.1:8080
echo.
echo Keep YOLO-Bridge window open for detection boxes.
echo Manual restart: ai\yolov8\live-bridge.bat

:END
pause
