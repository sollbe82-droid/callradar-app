@echo off
set A=C:\AndroidSdk\platform-tools\adb.exe
mkdir "C:\CallRadar\_releases\v101" 2>nul
copy /Y "C:\CallRadar\app\build\outputs\apk\onestore\release\app-onestore-release.apk" "C:\CallRadar\_releases\v101\CallRadar-v101-3.0.1-onestore.apk"
copy /Y "C:\CallRadar\app\build\outputs\mapping\onestoreRelease\mapping.txt" "C:\CallRadar\_releases\v101\mapping-v101-3.0.1-onestore.txt"
%A% devices
%A% install -r "C:\CallRadar\_releases\v101\CallRadar-v101-3.0.1-onestore.apk"
certutil -hashfile "C:\CallRadar\_releases\v101\CallRadar-v101-3.0.1-onestore.apk" MD5 | findstr /v ":"
cd /d C:\CallRadar
git add -A
git commit -m "v101 build"
