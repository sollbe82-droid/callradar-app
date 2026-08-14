@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
echo === DEVICES === > C:\CallRadar\installtest.log
%ADB% devices >> C:\CallRadar\installtest.log 2>&1
echo === INSTALL === >> C:\CallRadar\installtest.log
%ADB% install -r "C:\CallRadar\_releases\v56\callradar-v56-2.5.6-onestore.apk" >> C:\CallRadar\installtest.log 2>&1
echo === LAUNCH === >> C:\CallRadar\installtest.log
%ADB% shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 >> C:\CallRadar\installtest.log 2>&1
echo === DONE === >> C:\CallRadar\installtest.log
