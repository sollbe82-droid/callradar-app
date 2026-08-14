@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell input swipe 900 1300 900 750 250
timeout /t 1 /nobreak > nul
"%ADB%" shell uiautomator dump /sdcard/ui.xml > nul 2>&1
"%ADB%" pull /sdcard/ui.xml C:\CallRadar\_test\ui_now.xml > nul 2>&1
