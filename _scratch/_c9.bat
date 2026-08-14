@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\c9.log
git add app/src app/build.gradle.kts >> C:\CallRadar\c9.log 2>&1
git commit -m "v53 2.5.3 stage1: remove screen address parsing (GPS-only origin/dest), drop uber ghost-call condition (mokjeokji+dochak), remove START_SCREEN diagnostic, bump versionCode 53" >> C:\CallRadar\c9.log 2>&1
git log --oneline -3 >> C:\CallRadar\c9.log 2>&1
