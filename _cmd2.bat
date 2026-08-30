@echo off
del /q C:\CallRadar\_u_*.xml C:\CallRadar\_ok_*.txt C:\CallRadar\_crash_*.txt 2>nul
cd /d C:\CallRadar
git add -A
git commit -m "docs: late-cancel root cause, measured threshold, store release notes"
git log --oneline -1
