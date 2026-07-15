@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo Clear stream.file-path - back to RTSP cam1
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p='%~dp0server\src\main\resources\application.yml';" ^
  "$c=Get-Content -LiteralPath $p -Raw -Encoding UTF8;" ^
  "$c=[regex]::Replace($c,'(?m)^  file-path:\s*.*$','  file-path: \"\"');" ^
  "[IO.File]::WriteAllText($p,$c,(New-Object System.Text.UTF8Encoding $false));" ^
  "Write-Host 'file-path cleared'"
echo.
echo Restart Spring Boot to pull RTSP again.
pause
