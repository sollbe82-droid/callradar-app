@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
echo === INSTALL v57 === > C:\CallRadar\installv57.log
%ADB% install -r "C:\CallRadar\_releases\v57\callradar-v57-2.5.7-onestore.apk" >> C:\CallRadar\installv57.log 2>&1
%ADB% shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 >> C:\CallRadar\installv57.log 2>&1
echo === DONE === >> C:\CallRadar\installv57.log
