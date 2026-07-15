@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ========================================
echo  704 Sandbox Camera - Connection Check
echo ========================================
echo.
echo [1] mediamtx.yml cam1 source
findstr /C:"source: rtsp://" mediamtx.yml
echo.
echo [2] MediaMTX listening on 8554
netstat -an | findstr ":8554"
echo.
echo [3] Direct sandbox RTSP (VLC test)
echo   rtsp://10.126.59.120:8554/live/live12
echo.
echo [4] Local relay (after start-mediamtx.bat)
echo   rtsp://127.0.0.1:8554/cam1
echo.
echo If no video:
echo  - Ping or VLC test 10.126.59.120 (campus network)
echo  - Run stop-mediamtx.bat then start-mediamtx.bat
echo  - Switch channel: set-sandbox-camera.bat
echo  - See SANDBOX摄像头.txt
echo.
pause
