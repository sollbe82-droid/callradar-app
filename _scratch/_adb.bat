@echo off
cd /d C:\CallRadar
set ADB=C:\AndroidSdk\platform-tools\adb.exe
echo === DEVICES ===> C:\CallRadar\adb.log
"%ADB%" devices >> C:\CallRadar\adb.log 2>&1
echo === INSTALL ===>> C:\CallRadar\adb.log
"%ADB%" install -r "C:\CallRadar\app\build\outputs\apk\onestore\release\app-onestore-release.apk" >> C:\CallRadar\adb.log 2>&1
echo INSTALL_EXIT=%ERRORLEVEL%>> C:\CallRadar\adb.log
echo === PACKAGE ===>> C:\CallRadar\adb.log
"%ADB%" shell pm list packages | findstr callradar >> C:\CallRadar\adb.log 2>&1
echo === DONE %DATE% %TIME% ===>> C:\CallRadar\adb.log
