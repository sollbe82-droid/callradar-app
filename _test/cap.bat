@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell screencap -p /sdcard/s.png
"%ADB%" pull /sdcard/s.png C:\CallRadar\_test\screen.png > nul 2>&1
"%ADB%" shell uiautomator dump /sdcard/ui.xml > nul 2>&1
"%ADB%" pull /sdcard/ui.xml C:\CallRadar\_test\ui_now.xml > nul 2>&1
