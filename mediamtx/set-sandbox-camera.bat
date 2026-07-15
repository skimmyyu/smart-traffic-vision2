@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo ========================================
echo  704 sandbox camera - switch RTSP channel
echo  rtsp://10.126.59.120:8554/live/liveN
echo ========================================
echo.
echo  live1 bridge    live7  road2
echo  live2 parking out live8  tunnel accident
echo  live6 bridge in live12 road1 (default)
echo.
set /p CHANNEL=Enter channel 1-12 (default 12): 
if "%CHANNEL%"=="" set CHANNEL=12
set RTSP_URL=rtsp://10.126.59.120:8554/live/live%CHANNEL%
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0\_patch_mediamtx_source.ps1" "%RTSP_URL%"
echo.
echo Updated: %RTSP_URL%
echo Restart start-mediamtx.bat
pause
