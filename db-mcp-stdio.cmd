@echo off
rem Configure the JDK, JAR and JVM/Spring options at the top of db-mcp-stdio.ps1.
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0db-mcp-stdio.ps1"
exit /b %ERRORLEVEL%
