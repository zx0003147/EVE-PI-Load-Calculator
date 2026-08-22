@echo off
rem EVE PI Load Calculator - GUI launcher (double-click to start).
rem Requires Java 21+ (java/javaw) on PATH.
setlocal
cd /d "%~dp0"

where javaw >nul 2>nul
if errorlevel 1 (
    where java >nul 2>nul
    if errorlevel 1 (
        echo Java runtime not found. Install Java 21+ and make sure "java" is on PATH.
        pause
        exit /b 1
    )
    java --enable-native-access=ALL-UNNAMED -cp "out\classes;lib\*" com.vepi.app.DesktopMain %*
    if errorlevel 1 pause
    exit /b %errorlevel%
)

start "" javaw --enable-native-access=ALL-UNNAMED -cp "out\classes;lib\*" com.vepi.app.DesktopMain %*
endlocal
