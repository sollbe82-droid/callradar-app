@echo off
chcp 65001 >nul
title 업데이트 팝업 해제

REM ============================================================
REM  테스트 설정을 지웁니다. min_version 을 비우면 팝업이 사라집니다.
REM  아래 KEY= 뒤에 ADMIN_KEY 를 붙여넣고 실행하세요.
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
echo  업데이트 팝업 해제 중...
curl.exe -sS -m 90 -X POST -H "x-admin-key: %KEY%" -H "Content-Type: application/json" ^
  -d "{\"min_version\":\"\",\"reason\":\"\",\"force\":false}" ^
  "%B%/api/admin/update-notice"
echo.
echo.
echo  해제됐습니다. 확인:
curl.exe -sS -m 90 -H "x-admin-key: %KEY%" "%B%/api/admin/update-notice"
echo.
pause
