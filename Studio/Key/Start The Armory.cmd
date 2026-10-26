@echo off
rem Starts The Armory from this USB key: HyCreator, Blockbench, PhotoCraft and her copy of the mod, with
rem every setting and her sign-in kept on the key, so the computer it runs on is left as it was.
rem The builder puts this file at the top of the key. It runs the window from her copy of the mod, so a fix
rem reaches the key with her next update, or from the key's own copy when her branch has none.
rem Every program is named by its full path: some computers never run a program by its bare name.
setlocal
set "KEY=%~dp0"
set "WINDOW=%KEY%The-Armory-Mod\Studio\Key\start.ps1"
if not exist "%WINDOW%" set "WINDOW=%KEY%Key\start.ps1"
if not exist "%WINDOW%" (
    echo The Armory cannot start: its files are missing from this key. Send Larsonix a screenshot of this window.
    pause
    exit /b 1
)
start "" "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%WINDOW%" -Key "%KEY%."
