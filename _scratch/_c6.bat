@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\c6.log
git add app/src >> C:\CallRadar\c6.log 2>&1
git commit -m "fix #7 franchise call-pause false trip (operator precedence + exclude colmeum/accept), #2-3 track distance jitter gate (700km fix), #5 floating hide when manual off+idle, #1 radar map center on my location + recenter button (no more cluster jump)" >> C:\CallRadar\c6.log 2>&1
git log --oneline -3 >> C:\CallRadar\c6.log 2>&1
