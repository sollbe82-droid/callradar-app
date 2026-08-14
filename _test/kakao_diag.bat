@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
set KT=keytool
if exist "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" set KT="C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
del /f /q C:\CallRadar\_test\kakao.log 2>nul
echo === MAP/KAKAO ERRORS (logcat) === > C:\CallRadar\_test\kakao.log
"%ADB%" logcat -d -t 600 2>&1 | findstr /I "kakao vectormap KakaoMap authentic keyhash key hash appkey 401 403 인증" >> C:\CallRadar\_test\kakao.log
echo === apk path === >> C:\CallRadar\_test\kakao.log
for /f "tokens=2 delims=:" %%p in ('"%ADB%" shell pm path com.callradar.app') do set APKPATH=%%p
echo path=%APKPATH% >> C:\CallRadar\_test\kakao.log
"%ADB%" shell cp %APKPATH% /sdcard/cr_base.apk >> C:\CallRadar\_test\kakao.log 2>&1
"%ADB%" pull /sdcard/cr_base.apk C:\CallRadar\_test\cr_base.apk >> C:\CallRadar\_test\kakao.log 2>&1
echo === SIGNING CERT (installed v22) === >> C:\CallRadar\_test\kakao.log
%KT% -printcert -jarfile C:\CallRadar\_test\cr_base.apk >> C:\CallRadar\_test\kakao.log 2>&1
echo DONE >> C:\CallRadar\_test\kakao.log
