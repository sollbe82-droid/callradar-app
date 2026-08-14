@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
echo === INSTALL v59 === > C:\CallRadar\installv59.log
%ADB% install -r "C:\CallRadar\_releases\v59\callradar-v59-2.5.9-onestore.apk" >> C:\CallRadar\installv59.log 2>&1
%ADB% shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 >> C:\CallRadar\installv59.log 2>&1
echo === DONE === >> C:\CallRadar\installv59.log
