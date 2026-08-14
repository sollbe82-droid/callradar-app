@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
echo === %DATE% %TIME% ===> C:\CallRadar\dev.log
"%ADB%" devices >> C:\CallRadar\dev.log 2>&1
"%ADB%" get-state >> C:\CallRadar\dev.log 2>&1
