@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\c11.log
git add app/src >> C:\CallRadar\c11.log 2>&1
git commit -m "v53 stage3: Samsung restricted-settings accessibility guide (row in setup dialog when acc off) + proactive auto-surface after update drops accessibility (#103/#124)" >> C:\CallRadar\c11.log 2>&1
git log --oneline -2 >> C:\CallRadar\c11.log 2>&1
