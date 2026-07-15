@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Stopping MediaMTX...
taskkill /IM mediamtx.exe /F >nul 2>&1
if errorlevel 1 (
  echo No mediamtx.exe process found.
) else (
  echo MediaMTX stopped.
)
timeout /t 1 >nul
