@echo off
set L=C:\CallRadar\_i.log
set ADB=C:\AndroidSdk\platform-tools\adb.exe
set APK=C:\CallRadar\app\build\outputs\apk\onestore\debug\app-onestore-debug.apk
echo START > %L%
echo ---APK--- >> %L%
dir %APK% >> %L% 2>&1
echo ---DEVICES--- >> %L%
%ADB% kill-server >> %L% 2>&1
%ADB% start-server >> %L% 2>&1
%ADB% devices -l >> %L% 2>&1
echo ---INSTALL--- >> %L%
%ADB% install -r %APK% >> %L% 2>&1
echo INSTALL_RC %ERRORLEVEL% >> %L%
echo ---VERSION ON PHONE--- >> %L%
%ADB% shell dumpsys package com.callradar.app ^| findstr versionName >> %L% 2>&1
echo DONE >> %L%
