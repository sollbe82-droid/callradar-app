@echo off
echo START> C:\CallRadar\c23.log
git -C C:\CallRadar add app/src >> C:\CallRadar\c23.log 2>&1
git -C C:\CallRadar commit -m "v54: radar HERO combines my+all-driver dest-risk (mix label), learning card shows correction count not misleading pct, LPG gas receipt liters parse (exact 3-decimal, comma-safe)" >> C:\CallRadar\c23.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c23.log 2>&1
