@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
findstr /C:"_releases/" .gitignore >nul 2>&1 || echo _releases/>> .gitignore
echo START> C:\CallRadar\c7.log
git add CLAUDE.md .gitignore >> C:\CallRadar\c7.log 2>&1
git commit -m "docs: release archive rule (keep mapping.txt+APK per version in _releases/) + v51 current status" >> C:\CallRadar\c7.log 2>&1
git log --oneline -2 >> C:\CallRadar\c7.log 2>&1
