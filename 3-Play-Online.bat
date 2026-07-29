@echo off
REM One online player. Run 2-Start-Server.bat first (in its own window,
REM leave it running), then run this once PER PLAYER - each one opens its
REM own window. You need at least 2 running at once to actually play:
REM nothing is playable until the server confirms a real second participant.
chcp 65001 >nul
cd /d "%~dp0"
if not exist out (
    echo The "out" folder is missing - run compile.bat first.
    pause
    exit /b 1
)
echo A login window will open. Leave Server host/port as the defaults if the
echo server is on this PC. Username / Password: pick anything - first login
echo creates the account.
echo.
java -cp "out;lib\slf4j-api.jar;lib\slf4j-nop.jar;lib\sqlite-jdbc.jar" NetworkClientDemo
pause
