@echo off
REM 콜레이더 일일 건강검진 - 관리자 API 결과를 파일로 저장
REM 더블클릭으로 실행하면 C:\CallRadar\health_latest.json 에 저장됩니다.
setlocal
set KEY=90d96600658fbf204a3032a69455e8b8
set OUT=C:\CallRadar\health_latest.json
set OUT2=C:\CallRadar\health_worksessions.json

echo [1/2] testers-data 가져오는 중... (Render 콜드스타트면 30~60초 걸립니다)
curl.exe -s --max-time 120 "https://callradar-server.onrender.com/api/admin/testers-data?key=%KEY%&cb=%RANDOM%" -o "%OUT%"

echo [2/2] work-sessions 가져오는 중...
curl.exe -s --max-time 120 "https://callradar-server.onrender.com/api/admin/work-sessions?key=%KEY%&cb=%RANDOM%" -o "%OUT2%"

echo.
echo 저장 완료:
echo   %OUT%
echo   %OUT2%
echo.
echo 클로드에게 "헬스체크 파일 읽어줘" 라고 하면 분석합니다.
pause
