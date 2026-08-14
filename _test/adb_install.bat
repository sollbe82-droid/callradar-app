@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
set APK=C:\CallRadar\app\build\outputs\apk\release\app-release.apk
echo === installing %APK% === > C:\CallRadar\_test\install.log
"%ADB%" install -r "%APK%" >> C:\CallRadar\_test\install.log 2>&1
echo INSTALL_EXIT=%errorlevel% >> C:\CallRadar\_test\install.log
echo === version after === >> C:\CallRadar\_test\install.log
"%ADB%" shell "dumpsys package com.callradar.app | grep -E 'versionCode|versionName'" >> C:\CallRadar\_test\install.log 2>&1
echo === DONE === >> C:\CallRadar\_test\install.log
