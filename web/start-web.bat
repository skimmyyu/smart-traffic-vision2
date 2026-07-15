@echo off
setlocal EnableExtensions
chcp 65001 >nul 2>&1
cd /d "%~dp0"

if not exist node_modules (
  echo Installing dependencies...
  call npm install
  if errorlevel 1 (
    echo [ERROR] npm install failed
    pause
    exit /b 1
  )
)

echo.
echo Frontend (local):  http://localhost:5173/monitor
echo Frontend (LAN):    use the Network URL shown below + /monitor
echo Backend:           http://127.0.0.1:8080
echo.
echo If other PCs cannot open the LAN URL, run open-lan-firewall.bat as Administrator once.
echo.

call npm run dev
pause
