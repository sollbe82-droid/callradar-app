@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" logcat -c
"%ADB%" shell monkey -p com.callradar.app --throttle 150 --pct-syskeys 0 --ignore-security-exceptions --ignore-timeouts --monitor-native-crashes -v -v 3000 > C:\CallRadar\_test\monkey.log 2>&1
echo === CRASH BUFFER === > C:\CallRadar\_test\crash.log
"%ADB%" logcat -d -b crash >> C:\CallRadar\_test\crash.log 2>&1
echo === ERRORS (main) === >> C:\CallRadar\_test\crash.log
"%ADB%" logcat -d *:E -t 400 >> C:\CallRadar\_test\crash.log 2>&1
echo MONKEY_DONE
