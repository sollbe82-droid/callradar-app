@echo off
echo START> C:\CallRadar\c21.log
git -C C:\CallRadar add app/src >> C:\CallRadar\c21.log 2>&1
git -C C:\CallRadar commit -m "v54 5/6: radar voice guidance reads destPooled, phrases money-destination as GPS-location / all-driver based for clarity" >> C:\CallRadar\c21.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c21.log 2>&1
