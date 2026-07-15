@echo off
setlocal EnableExtensions
chcp 65001 >nul 2>&1
cd /d "%~dp0"

echo ========================================
echo  Initialize MySQL: traffic_db
echo ========================================
echo.
echo Runs server\sql\init.sql
echo Username: root
echo Password: 123456 (or enter when prompted)
echo.

set "MYSQL=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
if not exist "%MYSQL%" (
  where mysql >nul 2>&1
  if errorlevel 1 (
    echo [ERROR] mysql not found. Install MySQL 8+ or open server\sql\init.sql in Workbench.
    pause
    exit /b 1
  )
  set "MYSQL=mysql"
)

"%MYSQL%" -u root -p123456 < server\sql\init.sql
if errorlevel 1 (
  echo.
  echo [WARN] Auto password failed. Trying interactive prompt...
  "%MYSQL%" -u root -p < server\sql\init.sql
  if errorlevel 1 (
    echo [ERROR] init.sql failed. Check MySQL service and password.
    pause
    exit /b 1
  )
)

echo.
echo [OK] Database ready. Tables should include:
echo   devices, whitelist, plate_records, alerts, congestion_logs
echo   parking_zones, road_segments, camera_road_rois
echo.
echo Next: restart server\start-server.bat
pause
