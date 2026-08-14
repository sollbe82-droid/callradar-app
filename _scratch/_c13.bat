@echo off
echo START> C:\CallRadar\c13.log
git -C C:\CallRadar add app/src >> C:\CallRadar\c13.log 2>&1
git -C C:\CallRadar commit -m "v53 stage4b: expense tab summary adds LPG total liters + company discount (per-liter x liters); calendar cell taller (aspectRatio 0.72) to stop expense clipping" >> C:\CallRadar\c13.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c13.log 2>&1
