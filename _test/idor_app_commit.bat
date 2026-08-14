@echo off
cd /d C:\CallRadar
del /f /q .git\index.lock 2>nul
set GIT=git
where git >nul 2>&1 || set GIT="C:\Program Files\Git\cmd\git.exe"
%GIT% add app/src/main/java/com/callradar/app > C:\CallRadar\_test\idor_app.log 2>&1
%GIT% commit -m "security(v24): client IDOR - attach bearer token on all API calls, token lifecycle (issue/self-heal/clear)" >> C:\CallRadar\_test\idor_app.log 2>&1
echo COMMIT_EXIT=%errorlevel% >> C:\CallRadar\_test\idor_app.log
%GIT% log --oneline -1 >> C:\CallRadar\_test\idor_app.log 2>&1
%GIT% show --stat --oneline HEAD >> C:\CallRadar\_test\idor_app.log 2>&1
