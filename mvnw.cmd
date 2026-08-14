@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0mvnw.ps1" %*
exit /b %ERRORLEVEL%
