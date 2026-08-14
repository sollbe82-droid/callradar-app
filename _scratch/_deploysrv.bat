@echo off
cd /d C:\CallRadar\server
echo === SERVER DEPLOY === > C:\CallRadar\deploysrv.log
git -C C:\CallRadar\server add index.js >> C:\CallRadar\deploysrv.log 2>&1
git -C C:\CallRadar\server commit -m "v56: work_sessions_log table + /api/work-session/close + admin work-sessions (session km/time summary for diagnosis)" >> C:\CallRadar\deploysrv.log 2>&1
git -C C:\CallRadar\server push >> C:\CallRadar\deploysrv.log 2>&1
git -C C:\CallRadar\server log --oneline -1 >> C:\CallRadar\deploysrv.log 2>&1
echo === DONE === >> C:\CallRadar\deploysrv.log
