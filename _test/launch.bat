@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 > nul 2>&1
timeout /t 3 /nobreak > nul
"%ADB%" shell uiautomator dump /sdcard/ui.xml > nul 2>&1
"%ADB%" pull /sdcard/ui.xml C:\CallRadar\_test\ui_now.xml > nul 2>&1
