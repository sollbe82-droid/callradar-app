@echo off
echo START> C:\CallRadar\c19.log
git -C C:\CallRadar add app/src >> C:\CallRadar\c19.log 2>&1
git -C C:\CallRadar commit -m "v54 4: radar map my-location marker (blue dot at current GPS, follows every 2s via label.moveTo on DriverMapScreen)" >> C:\CallRadar\c19.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c19.log 2>&1
