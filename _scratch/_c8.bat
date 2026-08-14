@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\c8.log
git add app/src app/build.gradle.kts CLAUDE.md >> C:\CallRadar\c8.log 2>&1
git commit -m "v52 2.5.2: strengthen franchise auto-dispatch call-card false-trip exclusion + START_SCREEN diagnostic + bump versionCode 52" >> C:\CallRadar\c8.log 2>&1
git log --oneline -3 >> C:\CallRadar\c8.log 2>&1
