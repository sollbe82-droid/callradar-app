@echo off
del /q C:\CallRadar\_uu*.xml C:\CallRadar\_cr.txt C:\CallRadar\_cr2.txt C:\CallRadar\_u_*.xml C:\CallRadar\_va.json C:\CallRadar\_ua.json 2>nul
cd /d C:\CallRadar
git add -A
git commit -m "v101 release notes + store text"
git log --oneline -1
dir "C:\CallRadar\_releases\v101"
