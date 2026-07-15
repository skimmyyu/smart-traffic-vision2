@echo off
REM Parentheses-safe: no IF blocks containing paths with ()
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

set "PY=.venv\Scripts\python.exe"
if exist "%PY%" goto HAVE_VENV
echo [ERROR] .venv not found under:
echo   %CD%
echo Run from project root: setup-yolo-venv.bat
pause
exit /b 1

:HAVE_VENV
"%PY%" -c "import sys" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] .venv is broken ^(copied from another PC / wrong Python path^).
  echo Fix: run setup-yolo-venv.bat from project root, then retry.
  pause
  exit /b 103
)

set "WEIGHTS="
if exist "runs\detect\sandbox-car-v4\weights\best.pt" set "WEIGHTS=runs\detect\sandbox-car-v4\weights\best.pt"
if not defined WEIGHTS if exist "runs\detect\sandbox-car-v3\weights\best.pt" set "WEIGHTS=runs\detect\sandbox-car-v3\weights\best.pt"
if not defined WEIGHTS if exist "weights\yolov8n.pt" set "WEIGHTS=weights\yolov8n.pt"
if not defined WEIGHTS (
  echo [ERROR] No YOLO weights found.
  echo Expected one of:
  echo   runs\detect\sandbox-car-v4\weights\best.pt
  echo   runs\detect\sandbox-car-v3\weights\best.pt
  echo   weights\yolov8n.pt
  pause
  exit /b 1
)

echo ========================================
echo  YOLO Live Bridge
echo ========================================
echo Weights: %WEIGHTS%
echo Open http://localhost:5173/monitor
echo.
echo Waiting for backend http://127.0.0.1:8080 ...

set /a _wait=0
:WAIT_API
powershell -NoProfile -Command "try { $r=Invoke-WebRequest -Uri http://127.0.0.1:8080/api/models/active -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -eq 200) { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>&1
if %ERRORLEVEL%==0 goto RUN
set /a _wait+=2
timeout /t 2 /nobreak >nul
if %_wait% LSS 120 goto WAIT_API
echo [WARN] Backend still not ready after 120s, starting anyway...

:RUN
echo Backend check done. Starting bridge...
echo Close this window to stop detection boxes.
echo.

:LOOP
"%PY%" -u scripts\live_bridge.py --weights "%WEIGHTS%" --no-ensemble --source rtsp://127.0.0.1:8554/cam1 --interval 0.05 --conf 0.35 --imgsz 640 --infer-width 960 --flush-reads 8 --lead-ms 0 --channel-poll 0.3 --camera-poll 0.5 --min-area-ratio 0.0012 --max-aspect 2.8 --min-box-px 18 %*
set _ec=%ERRORLEVEL%
if "%_ec%"=="99" (
  echo [INFO] Another YOLO bridge is already running. This window will idle.
  echo        Close extra YOLO-Bridge windows; keep only ONE.
  timeout /t 30 /nobreak >nul
  goto LOOP
)
echo.
echo [WARN] live_bridge exited with code %_ec%. Restarting in 3s...
echo        Press Ctrl+C to stop.
timeout /t 3 /nobreak >nul
goto LOOP
