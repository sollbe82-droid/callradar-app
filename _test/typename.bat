@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" shell input tap 500 876
timeout /t 1 /nobreak > nul
"%ADB%" shell input text "Gangnam-11"
timeout /t 1 /nobreak > nul
"%ADB%" shell input keyevent 4
