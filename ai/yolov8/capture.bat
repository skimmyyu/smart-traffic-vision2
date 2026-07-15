@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo [ERROR] .venv not found. Run setup first.
  pause
  exit /b 1
)
echo Using: %cd%\.venv\Scripts\python.exe
".venv\Scripts\python.exe" scripts\capture_frames.py %*
echo.
pause
