@echo off
cd /d "%~dp0"
echo Compiling...
powershell -NoProfile -Command "if (Test-Path out) { Remove-Item -Recurse -Force out }; New-Item -ItemType Directory -Path out | Out-Null; $files = Get-ChildItem -Recurse -Filter *.java | Where-Object { $_.FullName -notlike '*\upload\*' } | Select-Object -ExpandProperty FullName; javac -cp 'lib\*' -d out $files"
if %errorlevel% neq 0 (
    echo.
    echo COMPILE FAILED - see errors above.
) else (
    echo.
    echo Compiled successfully into the "out" folder.
)
pause
