@echo off
cd /d C:\CallRadar\server
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\srv3.log
git add index.js >> C:\CallRadar\srv3.log 2>&1
git commit -m "v54: radar/personal topOrigins all-driver pooled fallback when personal thin (originPooled flag)" >> C:\CallRadar\srv3.log 2>&1
git push >> C:\CallRadar\srv3.log 2>&1
git log --oneline -1 >> C:\CallRadar\srv3.log 2>&1
