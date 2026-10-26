@echo off
rem Makes a release of The Armory: a draft, a draft with a message for us, or a published version.
rem It asks every question it needs, and changes nothing when it cannot finish.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Tools\release.ps1" %*
pause
