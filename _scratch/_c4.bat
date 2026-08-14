@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\c4.log
git add app/src >> C:\CallRadar\c4.log 2>&1
git commit -m "radar map: on-map cycle filter (today/30k/50k) + 4-tier density colors (green/orange/red/black), remove bottom toggle" >> C:\CallRadar\c4.log 2>&1
echo EXIT=%ERRORLEVEL%>> C:\CallRadar\c4.log
git log --oneline -3 >> C:\CallRadar\c4.log 2>&1
