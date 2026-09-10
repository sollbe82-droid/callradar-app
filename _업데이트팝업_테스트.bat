@echo off
chcp 65001 >nul
title 업데이트 팝업 테스트

REM ============================================================
REM  아래 KEY= 뒤에 ADMIN_KEY 를 붙여넣고 저장한 뒤 실행하세요.
REM  (Render 환경변수 ADMIN_KEY 값)
REM
REM  이 배치는 "3.1.1 미만이면 팝업" 으로 설정합니다.
REM  지금 폰에 깔린 게 3.1.0 이라 팝업이 떠야 정상입니다.
REM  확인이 끝나면 같은 폴더의 _업데이트팝업_해제.bat 를 실행해
REM  반드시 원래대로 되돌리세요. (안 되돌리면 모든 기사에게 팝업이 뜹니다)
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
echo  [1] 현재 설정과 버전 분포 조회
curl.exe -sS -m 90 -H "x-admin-key: %KEY%" "%B%/api/admin/update-notice"
echo.
echo.
echo  [2] 테스트 설정 적용 (3.1.1 미만이면 팝업)
curl.exe -sS -m 90 -X POST -H "x-admin-key: %KEY%" -H "Content-Type: application/json" ^
  -d "{\"min_version\":\"3.1.1\",\"reason\":\"[테스트] 운행기록이 삭제되던 문제를 고쳤습니다.\",\"force\":false}" ^
  "%B%/api/admin/update-notice"
echo.
echo.
echo  적용됐습니다. 폰에서 앱을 완전히 종료했다가 다시 열어 보세요.
echo  확인이 끝나면 _업데이트팝업_해제.bat 를 실행하세요.
echo.
pause
