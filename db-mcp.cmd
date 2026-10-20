@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0db-mcp.ps1" %*
exit /b %ERRORLEVEL%
