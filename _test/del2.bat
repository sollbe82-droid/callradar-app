@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell input tap 1115 1188
ping -n 4 127.0.0.1 >nul
"%ADB%" shell screencap -p /sdcard/cr.png
"%ADB%" pull /sdcard/cr.png C:\CallRadar\_test\cr.png >nul 2>&1
echo DONE > C:\CallRadar\_test\step.log
