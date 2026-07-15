@echo off
REM Vehicle labeling (sandbox_labeled). For 异物 use open-labelimg-anomaly.bat
chcp 65001 >nul
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
  echo ERROR: venv not found.
  pause
  exit /b 1
)

if not exist ".venv\Lib\site-packages\labelImg\labelImg.py" (
  echo Installing labelImg...
  ".venv\Scripts\python.exe" -m pip install labelImg
)

echo Opening LabelImg for VEHICLE dataset (C:\yolo_data\sandbox_labeled)
echo Do NOT change Save Dir into smart-traffic-vision(2) — will crash.
echo For 异物 labeling, double-click: open-labelimg-anomaly.bat
echo.

".venv\Scripts\python.exe" "scripts\open_labelimg.py"
if errorlevel 1 (
  echo.
  echo Failed to open LabelImg.
  pause
  exit /b 1
)
