@echo off
chcp 65001 >nul
REM 서버 커밋만 한다(푸시=배포는 하지 않는다). 잠금파일 정리 포함.
cd /d C:\CallRadar\server
echo SRV_COMMIT_START %DATE% %TIME% > C:\CallRadar\_srv_commit.log
if exist .git\index.lock del /f /q .git\index.lock
git add index.js >> C:\CallRadar\_srv_commit.log 2>&1
git -c user.name=claude -c user.email=dev@callradar.local commit -F C:\CallRadar\_srv_msg.txt >> C:\CallRadar\_srv_commit.log 2>&1
echo COMMIT_EXITCODE %ERRORLEVEL% >> C:\CallRadar\_srv_commit.log
git log --oneline -2 >> C:\CallRadar\_srv_commit.log 2>&1
git status --short >> C:\CallRadar\_srv_commit.log 2>&1
echo SRV_COMMIT_DONE %DATE% %TIME% >> C:\CallRadar\_srv_commit.log
