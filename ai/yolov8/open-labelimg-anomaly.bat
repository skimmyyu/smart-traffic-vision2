@echo off
REM Open LabelImg for 异物(anomaly) dataset under C:\yolo_data (ASCII-safe)
chcp 65001 >nul
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
  echo ERROR: venv not found. Run setup-yolo-venv.ps1 first.
  pause
  exit /b 1
)

if not exist "C:\yolo_data\sandbox_anomaly\images\train" (
  echo Creating C:\yolo_data\sandbox_anomaly ...
  mkdir "C:\yolo_data\sandbox_anomaly\images\train" 2>nul
  mkdir "C:\yolo_data\sandbox_anomaly\images\val" 2>nul
  mkdir "C:\yolo_data\sandbox_anomaly\labels\train" 2>nul
  mkdir "C:\yolo_data\sandbox_anomaly\labels\val" 2>nul
)

if exist "datasets\sandbox_anomaly\classes.txt" (
  copy /Y "datasets\sandbox_anomaly\classes.txt" "C:\yolo_data\sandbox_anomaly\classes.txt" >nul
)
if exist "datasets\sandbox_anomaly\data.yaml" (
  copy /Y "datasets\sandbox_anomaly\data.yaml" "C:\yolo_data\sandbox_anomaly\data.yaml" >nul
)

if not exist ".venv\Lib\site-packages\labelImg\labelImg.py" (
  echo Installing labelImg...
  ".venv\Scripts\python.exe" -m pip install labelImg
)

echo.
echo Opening LabelImg for ANOMALY / debris
echo   Images: C:\yolo_data\sandbox_anomaly\images\train
echo   Labels: C:\yolo_data\sandbox_anomaly\labels\train
echo.
echo Do NOT change Save Dir to smart-traffic-vision(2) — will crash.
echo.

".venv\Scripts\python.exe" "scripts\open_labelimg.py" --anomaly
if errorlevel 1 (
  echo.
  echo Failed to open LabelImg.
  pause
  exit /b 1
)
