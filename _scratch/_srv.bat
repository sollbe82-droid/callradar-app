@echo off
cd /d C:\CallRadar\server
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\srv.log
git add index.js >> C:\CallRadar\srv.log 2>&1
git commit -m "v53 server: fix import/bulk 0-count (count expense-only rows)+store liters; add big_events table + admin register + radar/big-events query" >> C:\CallRadar\srv.log 2>&1
git push >> C:\CallRadar\srv.log 2>&1
git log --oneline -2 >> C:\CallRadar\srv.log 2>&1
