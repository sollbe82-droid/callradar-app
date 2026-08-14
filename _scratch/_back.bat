@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell input keyevent 4
ping -n 2 127.0.0.1 >nul
"%ADB%" shell input keyevent 4
ping -n 2 127.0.0.1 >nul
"%ADB%" exec-out screencap -p > C:\CallRadar\phone.png
