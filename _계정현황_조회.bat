@echo off
chcp 65001 >nul
title 계정 현황 조회

REM ============================================================
REM  아래 KEY= 뒤에 ADMIN_KEY 를 붙여넣고 저장한 뒤 실행하세요.
REM  (Render 환경변수 ADMIN_KEY 값)
REM
REM  결과는 C:\CallRadar\_accounts.json 에 저장됩니다.
REM  저장 후 클로드에게 "계정현황 나왔어" 라고 말씀하시면 제가 읽고 정리합니다.
REM ============================================================
set KEY=

if "%KEY%"=="" (
  echo.
  echo   [!] 이 파일을 메모장으로 열어 set KEY= 뒤에 ADMIN_KEY 를 넣고 저장하세요.
  echo.
  pause
  exit /b 1
)

set B=https://callradar-server.onrender.com

echo.
echo  계정 현황 조회 중... (서버가 깨어나는 데 30초 정도 걸릴 수 있습니다)
curl.exe -sS -m 150 -H "x-admin-key: %KEY%" "%B%/api/admin/accounts" -o C:\CallRadar\_accounts.json
echo.
echo  가입/유지 지표도 같이 받습니다.
curl.exe -sS -m 150 -H "x-admin-key: %KEY%" "%B%/api/admin/growth" -o C:\CallRadar\_growth.json
echo.
echo  저장됐습니다:
echo    C:\CallRadar\_accounts.json
echo    C:\CallRadar\_growth.json
echo.
echo  클로드에게 "계정현황 나왔어" 라고 말씀해 주세요.
echo.
pause
