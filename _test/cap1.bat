@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" logcat -c
"%ADB%" shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 > C:\CallRadar\_test\ui.log 2>&1
ping -n 6 127.0.0.1 >nul
"%ADB%" shell screencap -p /sdcard/cr.png
"%ADB%" pull /sdcard/cr.png C:\CallRadar\_test\cr.png >> C:\CallRadar\_test\ui.log 2>&1
"%ADB%" shell uiautomator dump /sdcard/ui.xml >> C:\CallRadar\_test\ui.log 2>&1
"%ADB%" pull /sdcard/ui.xml C:\CallRadar\_test\ui.xml >> C:\CallRadar\_test\ui.log 2>&1
echo DONE >> C:\CallRadar\_test\ui.log
