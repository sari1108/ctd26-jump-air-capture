@echo off
REM Local game: one window, one person controls both colors (hotseat).
REM No server needed. Run compile.bat first if you haven't yet.
chcp 65001 >nul
cd /d "%~dp0"
if not exist out (
    echo The "out" folder is missing - run compile.bat first.
    pause
    exit /b 1
)
java -cp out BoardDemo
pause
