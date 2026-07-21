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
echo Connecting to the server...
echo   - Server host: press Enter for "localhost" if the server is on this PC
echo   - Server port: press Enter for the default (5000)
echo   - Username / Password: pick anything - first login creates the account
echo.
java -cp "out;lib\slf4j-api.jar;lib\slf4j-nop.jar;lib\sqlite-jdbc.jar" NetworkClientDemo
pause
