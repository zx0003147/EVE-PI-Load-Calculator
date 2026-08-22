@echo off
rem Convenience wrapper: runs the single-source portable release script.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-portable.ps1"
exit /b %ERRORLEVEL%
