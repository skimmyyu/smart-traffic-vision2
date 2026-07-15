@echo off
setlocal EnableExtensions
chcp 65001 >nul 2>&1
cd /d "%~dp0"

echo ========================================
echo  Smart Traffic - Spring Boot Server
echo  Requires MediaMTX running
echo ========================================
echo.
echo API examples:
echo   http://127.0.0.1^:8080/api/system/status
echo   http://127.0.0.1^:8080/api/system/stream
echo   ws://127.0.0.1^:8080/ws/live
echo.
echo Press Ctrl+C to stop the server
echo.

call mvn spring-boot:run
if errorlevel 1 (
  echo.
  echo [ERROR] mvn spring-boot:run failed. Check Java 17 and Maven.
)

echo.
echo Server exited.
pause
