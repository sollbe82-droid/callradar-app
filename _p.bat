@echo off
set L=C:\CallRadar\_p.log
echo START > %L%
cd /d C:\CallRadar\server
cd >> %L% 2>&1
if exist .git\index.lock del /f /q .git\index.lock
del /f /q .fuse_hidden* >> %L% 2>&1
git add .gitignore >> %L% 2>&1
git commit -m "gitignore fuse_hidden shadow files" >> %L% 2>&1
echo ---BRANCH--- >> %L%
git rev-parse --abbrev-ref HEAD >> %L% 2>&1
echo ---REMOTE--- >> %L%
git remote -v >> %L% 2>&1
echo ---PUSH--- >> %L%
git push >> %L% 2>&1
echo PUSH_RC %ERRORLEVEL% >> %L%
echo ---LOG--- >> %L%
git log --oneline -3 >> %L% 2>&1
echo DONE >> %L%
