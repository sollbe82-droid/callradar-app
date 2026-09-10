@echo off
chcp 65001 >nul
set PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe
if not exist "%PY%" set PY=python
"%PY%" "C:\CallRadar\_video\pipeline\run_daily.py" >> "C:\CallRadar\_video\daily_runner.log" 2>&1
