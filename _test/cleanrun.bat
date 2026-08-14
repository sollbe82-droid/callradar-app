@echo off
C:\AndroidSdk\platform-tools\adb.exe logcat -d -b crash -t 200 > C:\CallRadar\_test\crash.log 2>&1
node C:\CallRadar\_test\cleanup.js
