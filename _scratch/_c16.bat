@echo off
echo START> C:\CallRadar\c16.log
git -C C:\CallRadar add app/src >> C:\CallRadar\c16.log 2>&1
git -C C:\CallRadar commit -m "v54: fix work-session distance bug (900km on stationary phone) - reject GPS segments with dt>60s (doze/GPS-gap jumps pass speed gate at low apparent speed)" >> C:\CallRadar\c16.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c16.log 2>&1
