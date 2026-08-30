@echo off
chcp 65001 >nul
REM ① server 배포(push → Render 자동배포)  ② .fuse_hidden 그림자 파일 정리
cd /d C:\CallRadar\server
echo PUSH_START %DATE% %TIME% > C:\CallRadar\_srv_push.log

echo ---- .fuse_hidden 정리 ---- >> C:\CallRadar\_srv_push.log
dir /b .fuse_hidden* >> C:\CallRadar\_srv_push.log 2>&1
del /f /q .fuse_hidden* >> C:\CallRadar\_srv_push.log 2>&1
echo 남은것: >> C:\CallRadar\_srv_push.log
dir /b .fuse_hidden* >> C:\CallRadar\_srv_push.log 2>&1

echo. >> C:\CallRadar\_srv_push.log
echo ---- .gitignore 커밋 ---- >> C:\CallRadar\_srv_push.log
if exist .git\index.lock del /f /q .git\index.lock
git add .gitignore >> C:\CallRadar\_srv_push.log 2>&1
git -c user.name=claude -c user.email=dev@callradar.local commit -m "gitignore: .fuse_hidden* (지운 파일의 옛 내용에 자격증명이 남는다)" >> C:\CallRadar\_srv_push.log 2>&1

echo. >> C:\CallRadar\_srv_push.log
echo ---- PUSH ---- >> C:\CallRadar\_srv_push.log
git push origin main >> C:\CallRadar\_srv_push.log 2>&1
echo PUSH_EXITCODE %ERRORLEVEL% >> C:\CallRadar\_srv_push.log
git log --oneline -3 >> C:\CallRadar\_srv_push.log 2>&1
echo PUSH_DONE %DATE% %TIME% >> C:\CallRadar\_srv_push.log
