@echo off
chcp 65001 >nul
title 자동기록 진단

REM ============================================================
REM  아래 set KEY= 뒤에 ADMIN_KEY 를 붙여넣고 저장한 뒤 실행하세요.
REM  (Render 환경변수 ADMIN_KEY 값)
REM
REM  결과는 C:\CallRadar\_diag\ 에 저장됩니다.
REM  끝나면 클로드에게 "진단 나왔어" 라고 말씀해 주세요.
REM ============================================================
set KEY=

if "%KEY%"=="" (
  echo.
  echo   [!] 메모장으로 열어 set KEY= 뒤에 ADMIN_KEY 를 넣고 저장하세요.
  echo.
  pause
  exit /b 1
)

set B=https://callradar-server.onrender.com
if not exist C:\CallRadar\_diag mkdir C:\CallRadar\_diag

echo.
echo  1/3 활성 유저 목록 (서버 깨우는 데 30초쯤 걸립니다)
curl.exe -sS -m 180 -H "x-admin-key: %KEY%" "%B%/api/admin/testers-data?cb=1" -o C:\CallRadar\_diag\testers.json

echo  2/3 이상징후
curl.exe -sS -m 180 -H "x-admin-key: %KEY%" "%B%/api/health/anomalies?days=14&cb=1" -o C:\CallRadar\_diag\anomalies.json

echo  3/3 스토어 분포
curl.exe -sS -m 180 -H "x-admin-key: %KEY%" "%B%/api/admin/stores?cb=1" -o C:\CallRadar\_diag\stores.json

echo.
echo  저장 완료:  C:\CallRadar\_diag\
echo  클로드에게 "진단 나왔어" 라고 말씀해 주세요.
echo.
pause
