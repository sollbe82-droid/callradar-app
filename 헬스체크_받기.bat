@echo off
chcp 65001 >nul
REM ─ 콜레이더 일일 건강검진 ────────────────────────────────────────
REM  더블클릭하면 관리자 API 결과를 C:\CallRadar\health_*.json 에 저장합니다.
REM
REM  [2026-08-27 변경] 키를 이 파일에 적지 않습니다.
REM   · 예전엔 여기에 ADMIN_KEY 를 박아두고 주소창에 ?key= 로 보냈습니다.
REM     그건 (1) 파일이 깃에 올라가면 키가 같이 올라가고
REM          (2) 주소에 키가 남아 로그·기록에 찍힙니다.
REM   · 이제 실행할 때 물어보고, 헤더(x-admin-key)로만 보냅니다.
REM     ?key= 는 서버에서 403 으로 막혀 있습니다(위치정보법 고시 제8조).
setlocal
set OUT=C:\CallRadar\health_latest.json
set OUT2=C:\CallRadar\health_worksessions.json

if "%CR_ADMIN_KEY%"=="" (
  set /p KEY=ADMIN_KEY 를 붙여넣고 Enter:
) else (
  set KEY=%CR_ADMIN_KEY%
)

echo.
echo [1/2] testers-data 가져오는 중... ^(Render 콜드스타트면 30~60초 걸립니다^)
curl.exe -s --max-time 120 -H "x-admin-key: %KEY%" "https://callradar-server.onrender.com/api/admin/testers-data?cb=%RANDOM%" -o "%OUT%"

echo [2/2] work-sessions 가져오는 중...
curl.exe -s --max-time 120 -H "x-admin-key: %KEY%" "https://callradar-server.onrender.com/api/admin/work-sessions?cb=%RANDOM%" -o "%OUT2%"

set KEY=
echo.
echo 저장 완료:
echo   %OUT%
echo   %OUT2%
echo.
echo 클로드에게 "헬스체크 파일 읽어줘" 라고 하면 분석합니다.
pause
