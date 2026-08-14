@echo off
echo START> C:\CallRadar\c12.log
git -C C:\CallRadar add app/src >> C:\CallRadar\c12.log 2>&1
git -C C:\CallRadar commit -m "v53 stage4a: rename calendar tab to wolbyeol + calendar cell sizing (date small, income/expense big), expense dialog fixed-height amount (no shake), liters in expense list, hide income column in expense import" >> C:\CallRadar\c12.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c12.log 2>&1
