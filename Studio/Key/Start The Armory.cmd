@echo off
rem Starts The Armory from this USB key: HyCreator, Blockbench, PhotoCraft and her copy of the mod, with
rem every setting and her sign-in kept on the key, so the computer it runs on is left as it was.
rem The builder puts this file at the top of the key. It runs the window from her copy of the mod, so a fix
rem reaches the key with her next update, or from the key's own copy when her branch has none.
rem The window hides this one once it is open, and this one waits for it, so a computer that refuses to
rem run it still shows why. It leaves the key's folder first, or Windows could not eject the key at Done.
rem Every program is named by its full path: some computers never run a program by its bare name.
setlocal
set "KEY=%~dp0"
set "WINDOW=%KEY%The-Armory-Mod\Studio\Key\start.ps1"
if not exist "%WINDOW%" set "WINDOW=%KEY%Key\start.ps1"
if not exist "%WINDOW%" (
    echo The Armory cannot start: its files are missing from this key. Unzip the key's zip onto it again.
    pause
    exit /b 1
)
cd /d "%SystemRoot%"
rem A newer PowerShell that started this passes on where it finds its modules, and Windows PowerShell then
rem cannot find its own basic commands. Without the setting, Windows PowerShell uses its own places.
set "PSModulePath="
echo Starting The Armory...
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%WINDOW%" -Key "%KEY%."
if errorlevel 1 (
    echo.
    echo The Armory could not start on this computer. Restart this computer, then start it again.
    pause
    exit /b 1
)
