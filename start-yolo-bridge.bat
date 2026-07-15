@echo off
REM Parentheses-safe launcher: delegates to PowerShell
setlocal EnableExtensions
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-yolo-bridge.ps1"
if errorlevel 1 pause
