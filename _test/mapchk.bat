@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell am force-stop com.callradar.app
ping -n 2 127.0.0.1 >nul
"%ADB%" logcat -c
"%ADB%" shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 >nul 2>&1
ping -n 5 127.0.0.1 >nul
"%ADB%" shell input tap 538 1987
ping -n 10 127.0.0.1 >nul
"%ADB%" shell screencap -p /sdcard/cr.png
"%ADB%" pull /sdcard/cr.png C:\CallRadar\_test\cr.png >nul 2>&1
"%ADB%" logcat -d -t 500 > C:\CallRadar\_test\maplog.txt 2>&1
echo DONE > C:\CallRadar\_test\step.log
