@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ========================================
echo  Local MP4 -^> MediaMTX cam1 (loop)
echo  Replaces sandbox camera for offline test
echo ========================================
echo.

set "VIDEO=%~1"
if "%VIDEO%"=="" (
  echo Drag-drop an mp4 onto this bat, or pass path as arg.
  echo.
  set /p VIDEO=Video path: 
)
if "%VIDEO%"=="" (
  echo No video specified.
  pause
  exit /b 1
)
if not exist "%VIDEO%" (
  echo File not found: %VIDEO%
  pause
  exit /b 1
)

REM Locate ffmpeg
set "FFMPEG="
where ffmpeg >nul 2>&1 && set "FFMPEG=ffmpeg"
if not defined FFMPEG if exist "%~dp0ffmpeg\bin\ffmpeg.exe" set "FFMPEG=%~dp0ffmpeg\bin\ffmpeg.exe"
if not defined FFMPEG if exist "%LOCALAPPDATA%\Microsoft\WinGet\Links\ffmpeg.exe" set "FFMPEG=%LOCALAPPDATA%\Microsoft\WinGet\Links\ffmpeg.exe"
if not defined FFMPEG (
  for /f "delims=" %%i in ('dir /b /s "%ProgramFiles%\ffmpeg*\bin\ffmpeg.exe" 2^>nul') do (
    if not defined FFMPEG set "FFMPEG=%%i"
  )
)
if not defined FFMPEG (
  echo [ERROR] ffmpeg not found.
  echo Install: winget install Gyan.FFmpeg
  echo Or put ffmpeg.exe on PATH.
  pause
  exit /b 1
)

echo Video : %VIDEO%
echo FFmpeg: %FFMPEG%
echo.

echo [1/3] Switch cam1 to publisher mode...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0_set_cam1_publisher.ps1" -Mode publisher
if errorlevel 1 (
  echo Failed to patch mediamtx.yml
  pause
  exit /b 1
)

echo [2/3] Restart MediaMTX...
call "%~dp0stop-mediamtx.bat"
start "MediaMTX" cmd /k "cd /d ""%~dp0"" && mediamtx.exe mediamtx.yml"
timeout /t 2 /nobreak >nul

echo [3/3] Push video loop to rtsp://127.0.0.1:8554/cam1
echo      Keep this window open while testing.
echo      Frontend / Java should use cam1 as usual.
echo      Ctrl+C to stop. Restore sandbox: restore-sandbox-camera.bat
echo.

REM -re = realtime; -stream_loop -1 = infinite; re-encode for RTSP compatibility
"%FFMPEG%" -hide_banner -loglevel warning -stats ^
  -re -stream_loop -1 -i "%VIDEO%" ^
  -an -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p ^
  -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:8554/cam1

echo.
echo Stream stopped.
pause
