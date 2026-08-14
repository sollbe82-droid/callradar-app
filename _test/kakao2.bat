@echo off
del /f /q C:\CallRadar\_test\kakao2.log 2>nul
del /f /q C:\CallRadar\_test\rawlog.txt 2>nul
set KT=
where keytool >nul 2>&1 && set KT=keytool
if "%KT%"=="" if exist "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" set KT="C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
if "%KT%"=="" if exist "C:\Program Files\Android\Android Studio1\jbr\bin\keytool.exe" set KT="C:\Program Files\Android\Android Studio1\jbr\bin\keytool.exe"
echo KT=%KT% > C:\CallRadar\_test\kakao2.log
echo === RELEASE APK CERT (upload key) === >> C:\CallRadar\_test\kakao2.log
%KT% -printcert -jarfile "C:\CallRadar\app\build\outputs\apk\release\app-release.apk" >> C:\CallRadar\_test\kakao2.log 2>&1
echo === full recent logcat to rawlog.txt === >> C:\CallRadar\_test\kakao2.log
C:\AndroidSdk\platform-tools\adb.exe logcat -d -t 1200 > C:\CallRadar\_test\rawlog.txt 2>&1
echo DONE >> C:\CallRadar\_test\kakao2.log
