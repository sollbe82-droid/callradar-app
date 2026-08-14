@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell input tap 1710 857
ping -n 3 127.0.0.1 >nul
"%ADB%" shell screencap -p /sdcard/cr.png
"%ADB%" pull /sdcard/cr.png C:\CallRadar\_test\cr.png >nul 2>&1
"%ADB%" shell uiautomator dump /sdcard/ui.xml >nul 2>&1
"%ADB%" pull /sdcard/ui.xml C:\CallRadar\_test\ui.xml >nul 2>&1
echo DONE > C:\CallRadar\_test\step.log
