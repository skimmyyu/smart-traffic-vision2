@echo off
chcp 65001 >nul
echo ========================================
echo  Smart Traffic - 防火墙放行（需管理员）
echo  IP 摄像头模式仅需本机 RTSP 8554
echo ========================================
echo.
netsh advfirewall firewall add rule name="SmartTraffic RTSP 8554 TCP" dir=in action=allow protocol=TCP localport=8554
echo.
echo 完成后：手机开 IP Webcam -^> start-mediamtx.bat -^> VLC 播放
echo rtsp://127.0.0.1:8554/live/cam1
echo.
pause
