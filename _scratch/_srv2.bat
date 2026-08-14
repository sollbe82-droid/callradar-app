@echo off
cd /d C:\CallRadar\server
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\srv2.log
git add index.js >> C:\CallRadar\srv2.log 2>&1
git commit -m "v54 5/6: region-aware money destinations in /api/radar/personal (filter by origin within 15km of GPS) + all-driver pooled fallback when personal thin (destPooled flag)" >> C:\CallRadar\srv2.log 2>&1
git push >> C:\CallRadar\srv2.log 2>&1
git log --oneline -1 >> C:\CallRadar\srv2.log 2>&1
