@echo off
chcp 65001 >nul
REM ─ server 폴더의 .fuse_hidden* 그림자 파일 23개를 지웁니다 ─────────
REM  이 파일들은 제가 _an*.js 에서 DB 비밀번호를 걷어낼 때 마운트가 남긴
REM  '지워진 원본'입니다. 즉 안에 옛 DB 접속문자열(비밀번호 포함)이 그대로 들어 있습니다.
REM  비밀번호는 이미 교체돼 죽었고 git 에도 안 올라갔지만, 디스크에 남길 이유가 없습니다.
REM  (.gitignore 에도 넣어놨으니 실수로 커밋될 일은 없습니다)
cd /d C:\CallRadar\server
echo FUSE_CLEAN_START %DATE% %TIME% > C:\CallRadar\_cleanup_fuse.log
dir /b .fuse_hidden* >> C:\CallRadar\_cleanup_fuse.log 2>&1
del /f /q .fuse_hidden* >> C:\CallRadar\_cleanup_fuse.log 2>&1
echo DEL_EXITCODE %ERRORLEVEL% >> C:\CallRadar\_cleanup_fuse.log
echo ---- 남은 것 ---- >> C:\CallRadar\_cleanup_fuse.log
dir /b .fuse_hidden* >> C:\CallRadar\_cleanup_fuse.log 2>&1
echo FUSE_CLEAN_DONE %DATE% %TIME% >> C:\CallRadar\_cleanup_fuse.log
