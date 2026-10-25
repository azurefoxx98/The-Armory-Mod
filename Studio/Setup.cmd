@echo off
rem Sets up this computer to work on The Armory in the game. Safe to run again.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Tools\setup.ps1" %*
pause
