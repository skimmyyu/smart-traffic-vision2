@echo off
setlocal EnableExtensions
chcp 65001 >nul 2>&1
cd /d "%~dp0"
echo ========================================
echo  Setup YOLO Python venv (local PC)
echo ========================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-yolo-venv.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] venv setup failed.
  pause
  exit /b 1
)
echo.
pause
