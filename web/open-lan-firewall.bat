@echo off
setlocal EnableExtensions
chcp 65001 >nul 2>&1

:: 需要管理员权限，为局域网访问放行端口
net session >nul 2>&1
if errorlevel 1 (
  echo 请右键「以管理员身份运行」本脚本
  pause
  exit /b 1
)

echo 正在为智慧交通系统添加 Windows 防火墙入站规则...
echo.

for %%P in (5173 8080 8888) do (
  netsh advfirewall firewall delete rule name="SmartTraffic TCP %%P" >nul 2>&1
  netsh advfirewall firewall add rule name="SmartTraffic TCP %%P" dir=in action=allow protocol=TCP localport=%%P
  if errorlevel 1 (
    echo [失败] 端口 %%P
  ) else (
    echo [成功] 已放行 TCP %%P
  )
)

echo.
echo 开发模式推荐：其它电脑访问 http://你的局域网IP:5173/monitor
echo   - 100.x.x.x  为 Tailscale，对方也需安装 Tailscale
echo   - 10.x.x.x   为校园/局域网，对方需在同一网段且无 AP 隔离
echo.
echo 若仍无法访问，请确认：
echo   1. 前端窗口已重启（npm run dev）
echo   2. 后端 start-server.bat 已运行
echo   3. 校园 WiFi 可能禁止设备互访，可改用有线网或 Tailscale
echo.
pause
