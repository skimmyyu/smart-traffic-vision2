@echo off
REM Stable 异物 labeler (tkinter) — use this instead of LabelImg
chcp 65001 >nul
cd /d "%~dp0"

set "PY=.venv\Scripts\python.exe"
if not exist "%PY%" (
  echo ERROR: .venv not found
  pause
  exit /b 1
)

if not exist "C:\yolo_data\sandbox_anomaly\images\train" (
  mkdir "C:\yolo_data\sandbox_anomaly\images\train" 2>nul
  mkdir "C:\yolo_data\sandbox_anomaly\labels\train" 2>nul
)

"%PY%" -c "import PIL" 1>nul 2>nul
if errorlevel 1 (
  echo Installing Pillow...
  "%PY%" -m pip install pillow
)

echo.
echo Opening simple 异物 labeler
echo   Images: C:\yolo_data\sandbox_anomaly\images\train
echo   Labels: C:\yolo_data\sandbox_anomaly\labels\train
echo.
echo Put images into images\train first, then draw boxes.
echo.

"%PY%" -u "scripts\simple_yolo_labeler.py" --dataset "C:\yolo_data\sandbox_anomaly"
if errorlevel 1 (
  echo.
  echo Failed.
  pause
)
