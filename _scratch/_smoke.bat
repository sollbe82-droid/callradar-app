@echo off
cd /d C:\CallRadar
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" logcat -c
"%ADB%" shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 > C:\CallRadar\smoke.log 2>&1
ping -n 10 127.0.0.1 >nul
"%ADB%" logcat -d -v brief > C:\CallRadar\logcat_full.log 2>&1
echo === SMOKE DONE %DATE% %TIME% ===>> C:\CallRadar\smoke.log
