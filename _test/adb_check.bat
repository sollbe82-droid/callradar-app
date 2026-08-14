@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
if not exist "%ADB%" set ADB=C:\Android\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb
echo === adb path: %ADB% === > C:\CallRadar\_test\adb.log
"%ADB%" version >> C:\CallRadar\_test\adb.log 2>&1
echo === devices === >> C:\CallRadar\_test\adb.log
"%ADB%" devices -l >> C:\CallRadar\_test\adb.log 2>&1
echo === CallRadar installed version === >> C:\CallRadar\_test\adb.log
"%ADB%" shell "dumpsys package com.callradar.app | grep -E 'versionCode|versionName'" >> C:\CallRadar\_test\adb.log 2>&1
echo === current focused app === >> C:\CallRadar\_test\adb.log
"%ADB%" shell "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'" >> C:\CallRadar\_test\adb.log 2>&1
echo === recent crash buffer === >> C:\CallRadar\_test\adb.log
"%ADB%" logcat -d -b crash -t 40 >> C:\CallRadar\_test\adb.log 2>&1
echo === DONE === >> C:\CallRadar\_test\adb.log
