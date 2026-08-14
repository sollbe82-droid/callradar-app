@echo off
echo START> C:\CallRadar\c17.log
git -C C:\CallRadar add app/src >> C:\CallRadar\c17.log 2>&1
git -C C:\CallRadar commit -m "v54: remove duplicate take-home card from wolbyeol (kept on home only) - de-dup per user" >> C:\CallRadar\c17.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c17.log 2>&1
