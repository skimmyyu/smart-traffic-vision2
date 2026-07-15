@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Stopping old server on port 8080...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
  taskkill /PID %%a /F >nul 2>&1
)
timeout /t 2 /nobreak >nul
echo Done.
