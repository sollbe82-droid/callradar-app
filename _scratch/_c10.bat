@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\c10.log
git add app/src >> C:\CallRadar\c10.log 2>&1
git commit -m "v53 stage2: uber realride tag (blue track)+boarded_at, uber 0won fare recovery from home last-trip, manual cancel via floating badge long-press (NaviIntentReceiver.instance)" >> C:\CallRadar\c10.log 2>&1
git log --oneline -3 >> C:\CallRadar\c10.log 2>&1
