@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell input tap %1 %2
ping -n 3 127.0.0.1 >nul
"%ADB%" exec-out screencap -p > C:\CallRadar\phone.png
echo TAP %1 %2 %TIME%> C:\CallRadar\tap.log
