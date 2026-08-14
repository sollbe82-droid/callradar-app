@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
echo === INSTALL v58 === > C:\CallRadar\installv58.log
%ADB% install -r "C:\CallRadar\_releases\v58\callradar-v58-2.5.8-onestore.apk" >> C:\CallRadar\installv58.log 2>&1
%ADB% shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 >> C:\CallRadar\installv58.log 2>&1
echo === DONE === >> C:\CallRadar\installv58.log
