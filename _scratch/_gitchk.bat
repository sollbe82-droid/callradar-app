@echo off
echo === check ===> C:\CallRadar\gitchk.log
git -C C:\CallRadar rev-parse --is-inside-work-tree >> C:\CallRadar\gitchk.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\gitchk.log 2>&1
git -C C:\CallRadar status --short >> C:\CallRadar\gitchk.log 2>&1
