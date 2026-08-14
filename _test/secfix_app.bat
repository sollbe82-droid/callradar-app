@echo off
cd /d C:\CallRadar
del /f /q .git\index.lock 2>nul
set GIT=git
where git >nul 2>&1 || set GIT="C:\Program Files\Git\cmd\git.exe"
%GIT% rm --cached callradar-release.jks > C:\CallRadar\_test\secfix_app.log 2>&1
echo *.jks>> .gitignore
echo local.properties>> .gitignore
%GIT% add .gitignore >> C:\CallRadar\_test\secfix_app.log 2>&1
%GIT% commit -m "security: untrack release keystore and local.properties" >> C:\CallRadar\_test\secfix_app.log 2>&1
echo COMMIT_EXITCODE=%errorlevel% >> C:\CallRadar\_test\secfix_app.log
%GIT% log --oneline -1 >> C:\CallRadar\_test\secfix_app.log 2>&1
