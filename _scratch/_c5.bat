@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\c5.log
git add app/src >> C:\CallRadar\c5.log 2>&1
git commit -m "fix #6 track loaded flag for auto-record (was all gray/deadhead) + #4 send boarded_at at boarding moment (near pickup no longer misses ride time)" >> C:\CallRadar\c5.log 2>&1
git log --oneline -2 >> C:\CallRadar\c5.log 2>&1
