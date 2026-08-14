@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" logcat -d -b crash -t 200 > C:\CallRadar\_test\crash.log 2>&1
"%ADB%" logcat -d -t 400 *:E > C:\CallRadar\_test\err.log 2>&1
