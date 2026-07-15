@echo off
chcp 65001 >nul
echo === Smart Traffic - Sandbox Camera Stream Check ===
echo.
echo Sandbox RTSP (12 channels, no auth):
echo   rtsp://10.126.59.120:8554/live/liveN  (N=1-12)
echo.
echo mediamtx.yml cam1 source:
findstr /C:"source: rtsp://" mediamtx.yml
echo.
echo Local VLC after MediaMTX started:
echo   rtsp://127.0.0.1:8554/cam1
echo.
echo MediaMTX ports:
netstat -an | findstr ":8554"
echo.
echo Steps: VLC test sandbox -^> start-mediamtx.bat -^> VLC cam1
pause
