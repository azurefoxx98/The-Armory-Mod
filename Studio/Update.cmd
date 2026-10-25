@echo off
rem Brings the mod's latest work into your branch, keeps yours, and sends both to GitHub.
rem It changes nothing when it cannot finish cleanly, and says why.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Tools\update.ps1" %*
pause
