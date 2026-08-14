@echo off
cd /d C:\CallRadar\server
echo === SERVER DEPLOY v57 === > C:\CallRadar\deploysrv2.log
git -C C:\CallRadar\server add index.js >> C:\CallRadar\deploysrv2.log 2>&1
git -C C:\CallRadar\server commit -m "v57: PUT /api/trips accepts business_date (client sends completion-based day when day_start set) - midnight trip attribution" >> C:\CallRadar\deploysrv2.log 2>&1
git -C C:\CallRadar\server push >> C:\CallRadar\deploysrv2.log 2>&1
git -C C:\CallRadar\server log --oneline -1 >> C:\CallRadar\deploysrv2.log 2>&1
echo === DONE === >> C:\CallRadar\deploysrv2.log
