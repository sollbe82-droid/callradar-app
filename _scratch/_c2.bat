@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START %DATE% %TIME%> C:\CallRadar\c2.log
git add app/src app/build.gradle.kts CLAUDE.md >> C:\CallRadar\c2.log 2>&1
git commit -m "v51 2.5.1: bump versionCode 51 + uber idle-home guard tighten + radar nav TODO" >> C:\CallRadar\c2.log 2>&1
echo EXIT=%ERRORLEVEL%>> C:\CallRadar\c2.log
git log --oneline -3 >> C:\CallRadar\c2.log 2>&1
