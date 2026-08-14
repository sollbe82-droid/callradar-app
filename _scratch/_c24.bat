@echo off
echo START> C:\CallRadar\c24.log
git -C C:\CallRadar add app/src >> C:\CallRadar\c24.log 2>&1
git -C C:\CallRadar commit -m "v54 fix: revert HERO dest-risk to resilient personal-first/all-fallback (always-merge broke it on large all-driver fetch + nationwide pollution); keep learning-count card + LPG liters parse" >> C:\CallRadar\c24.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c24.log 2>&1
