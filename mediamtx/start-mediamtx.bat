@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo Starting MediaMTX (704 sandbox camera)...
echo.
call "%~dp0stop-mediamtx.bat"
echo.
echo [1] Sandbox RTSP: see mediamtx.yml (default live12)
echo [2] Preview all cameras in VLC: rtsp://10.126.59.120:8554/live/liveN
echo [3] Java path: set-sandbox-camera.bat then VLC rtsp://127.0.0.1:8554/cam1
echo.
if not exist mediamtx.exe (
  echo ERROR: mediamtx.exe not found in this folder.
  goto END
)
mediamtx.exe mediamtx.yml
if errorlevel 1 (
  echo.
  echo MediaMTX exited with error. Check mediamtx.yml encoding/syntax.
)
:END
echo.
echo Press any key to close...
pause >nul
