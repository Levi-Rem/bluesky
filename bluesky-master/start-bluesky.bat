@echo off
setlocal EnableExtensions

rem One-click Windows entry point. Keep all paths relative to this file so the
rem launcher also works when it is started from File Explorer.
cd /d "%~dp0"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-bluesky.ps1" %*
set "BLUESKY_EXIT_CODE=%ERRORLEVEL%"

if not "%BLUESKY_EXIT_CODE%"=="0" (
    echo.
    echo BlueSky did not start successfully. See the error above.
    pause
)

exit /b %BLUESKY_EXIT_CODE%
