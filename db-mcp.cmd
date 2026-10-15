@echo off
if "%~1"=="" goto default_restart
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0db-mcp.ps1" %*
exit /b %ERRORLEVEL%

:default_restart
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0db-mcp.ps1" restart
exit /b %ERRORLEVEL%
