@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell input tap 100 2071
ping -n 4 127.0.0.1 >nul
"%ADB%" exec-out screencap -p > C:\CallRadar\home_now.png
