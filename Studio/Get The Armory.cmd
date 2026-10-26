@echo off
rem Gets The Armory onto this computer, on Lady's branch, and sets it up. The mod's team sends her
rem this file once. HyCreator's own Clone takes main, which has no Studio folder and, without the line
rem ending rules of the later branches, shows every file as changed, so its Branches list can
rem never switch away from it. Running it again is safe: an existing copy is kept and set up again.
rem Run from the Studio folder of a copy, it sets up that copy. Tests set ARMORY_SOURCE to a local
rem copy and pass setup's own test switches after the file name.
setlocal EnableDelayedExpansion
cd /d "%~dp0"
if not defined ARMORY_SOURCE set "ARMORY_SOURCE=https://github.com/azurefoxx98/The-Armory-Mod.git"
set "COPY=%~dp0The-Armory-Mod"
if exist "%~dp0Tools\setup.ps1" if exist "%~dp0..\.git" for %%c in ("%~dp0..") do set "COPY=%%~fc"
echo Getting The Armory.
echo.
where git >nul 2>nul
if errorlevel 1 (
    echo Git is not installed. Install it from https://git-scm.com/download/win with its default choices, then double-click this file again.
    pause
    exit /b 1
)
if exist "%COPY%\.git" (
    echo The mod is already here: !COPY!
) else (
    echo Downloading The Armory, about 80 MB, into !COPY!
    git clone --quiet --branch lady "!ARMORY_SOURCE!" "!COPY!"
    if errorlevel 1 (
        echo.
        echo The download failed. Check your internet, then double-click this file again.
        pause
        exit /b 1
    )
)
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%COPY%\Studio\Tools\setup.ps1" %*
if errorlevel 1 (
    pause
    exit /b 1
)
echo.
echo Then open the mod in HyCreator: click Workspace at the top left, then Open mod folder..., and pick:
echo   %COPY%
pause
