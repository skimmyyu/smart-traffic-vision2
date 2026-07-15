@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo Restore cam1 to sandbox RTSP pull...
echo.
set /p CHANNEL=Channel 1-12 (default 3): 
if "%CHANNEL%"=="" set CHANNEL=3
set RTSP_URL=rtsp://10.126.59.120:8554/live/live%CHANNEL%
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0_set_cam1_publisher.ps1" -Mode sandbox -SandboxUrl "%RTSP_URL%"
echo.
echo Updated cam1 source: %RTSP_URL%
echo Restart MediaMTX: start-mediamtx.bat
pause
