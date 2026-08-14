@echo off
cd /d C:\CallRadar\server
del /f /q .git\index.lock 2>nul
set GIT=git
where git >nul 2>&1 || set GIT="C:\Program Files\Git\cmd\git.exe"
%GIT% add index.js > C:\CallRadar\_test\deploy.log 2>&1
%GIT% commit -m "knowhow/hotspot data validation (confirmed) + crowd endpoint" >> C:\CallRadar\_test\deploy.log 2>&1
%GIT% push origin HEAD >> C:\CallRadar\_test\deploy.log 2>&1
echo PUSH_EXITCODE=%errorlevel% >> C:\CallRadar\_test\deploy.log
%GIT% log --oneline -1 >> C:\CallRadar\_test\deploy.log 2>&1
