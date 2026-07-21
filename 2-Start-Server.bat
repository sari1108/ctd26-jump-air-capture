@echo off
REM The game server. Leave this window open and running - it's what the
REM online players (3-Play-Online.bat) connect to. Run compile.bat first
REM if you haven't yet. Keeps a persistent users.db (usernames/passwords/ELO).
chcp 65001 >nul
cd /d "%~dp0"
if not exist out (
    echo The "out" folder is missing - run compile.bat first.
    pause
    exit /b 1
)
echo Starting server on port 5000...
echo Leave this window open. Close it (or Ctrl+C) to stop the server.
echo.
java -cp "out;lib\slf4j-api.jar;lib\slf4j-nop.jar;lib\sqlite-jdbc.jar" ServerMain 5000 users.db
pause
