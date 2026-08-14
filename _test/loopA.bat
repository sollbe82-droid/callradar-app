@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" logcat -c
"%ADB%" shell input keyevent 4
ping -n 2 127.0.0.1 >nul
"%ADB%" shell input tap 904 1987
ping -n 2 127.0.0.1 >nul
for /L %%R in (1,1,3) do (
  for %%A in (1000 5000 12345 50000 100000 999999 3000 7500 25000 88000) do (
    "%ADB%" shell input tap 1699 189
    ping -n 3 127.0.0.1 >nul
    "%ADB%" shell input swipe 906 1450 906 650 250
    ping -n 2 127.0.0.1 >nul
    "%ADB%" shell input tap 906 837
    ping -n 2 127.0.0.1 >nul
    "%ADB%" shell input text %%A
    "%ADB%" shell input keyevent 4
    ping -n 2 127.0.0.1 >nul
    "%ADB%" shell input tap 1314 1714
    ping -n 3 127.0.0.1 >nul
  )
)
echo === CRASH === > C:\CallRadar\_test\loop.log
"%ADB%" logcat -d -b crash >> C:\CallRadar\_test\loop.log 2>&1
echo === ERRORS(main) === >> C:\CallRadar\_test\loop.log
"%ADB%" logcat -d *:E -t 600 >> C:\CallRadar\_test\loop.log 2>&1
echo LOOP_DONE >> C:\CallRadar\_test\loop.log
