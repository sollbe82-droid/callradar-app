@echo off
cd /d C:\CallRadar
git add -A
git commit -m "v111 3.1.1: single BusinessDay (was 5 copies), shared numerator for hourly/km rates, app self-checks its numbers; handover 09-10"
git log --oneline -1
echo ---SERVER---
cd /d C:\CallRadar\server
git add -A
git commit -m "health: work_rate_mismatch net + backup unblock"
git push
git log --oneline -1
echo COMMIT_ALL_DONE
