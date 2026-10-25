@echo off
rem Runs The Armory's checks on this copy of the mod now, and shows what they found.
rem They also run by themselves after every commit and pull, and HyCreator shows the result.
set "PY="
for /f "delims=" %%p in ('git -C "%~dp0.." config --get armory.python') do set "PY=%%p"
if not defined PY (
    echo The checks are not installed yet. Double-click Studio\Setup.cmd once.
    pause
    exit /b 1
)
"%PY%" "%~dp0Tools\Checks\check.py"
pause
