@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo [ERROR] .venv not found.
  pause
  exit /b 1
)
echo Capture all 12 views x 15 frames (interval 2.5s)
".venv\Scripts\python.exe" -u scripts\capture_all_views.py --per-view 15 --interval 2.5 %*
echo.
pause
