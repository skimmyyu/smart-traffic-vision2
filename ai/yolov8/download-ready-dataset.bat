@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo [ERROR] .venv not found.
  pause
  exit /b 1
)
echo Download small ready-made traffic dataset (from COCO128)...
".venv\Scripts\python.exe" -u scripts\download_ready_dataset.py
echo.
pause
