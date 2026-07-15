@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo [ERROR] .venv not found.
  pause
  exit /b 1
)
echo Build ready dataset by auto-labeling sandbox images with YOLOv8n...
".venv\Scripts\python.exe" -u scripts\build_ready_dataset.py --conf 0.35 %*
echo.
pause
