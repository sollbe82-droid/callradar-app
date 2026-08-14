@echo off
cd /d C:\CallRadar
del /f /q C:\CallRadar\_test\v23.log 2>nul
where java >nul 2>&1
if errorlevel 1 if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call gradlew.bat assembleRelease --console=plain > C:\CallRadar\_test\v23.log 2>&1
echo ASM_EXIT=%errorlevel% >> C:\CallRadar\_test\v23.log
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" install -r "C:\CallRadar\app\build\outputs\apk\release\app-release.apk" >> C:\CallRadar\_test\v23.log 2>&1
echo INSTALL_EXIT=%errorlevel% >> C:\CallRadar\_test\v23.log
"%ADB%" shell "dumpsys package com.callradar.app | grep versionCode" >> C:\CallRadar\_test\v23.log 2>&1
echo DONE >> C:\CallRadar\_test\v23.log
