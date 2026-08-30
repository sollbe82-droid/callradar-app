@echo off
set A=C:\AndroidSdk\platform-tools\adb.exe
del /q C:\CallRadar\_bstep.txt C:\CallRadar\_lc.txt C:\CallRadar\_lc2.txt C:\CallRadar\_an.json 2>nul
copy /Y "C:\CallRadar\app\build\outputs\apk\onestore\release\app-onestore-release.apk" "C:\CallRadar\_releases\v100\CallRadar-v100-3.0.0-onestore.apk"
copy /Y "C:\CallRadar\app\build\outputs\mapping\onestoreRelease\mapping.txt" "C:\CallRadar\_releases\v100\mapping-v100-3.0.0-onestore.txt"
%A% install -r "C:\CallRadar\_releases\v100\CallRadar-v100-3.0.0-onestore.apk"
cd /d C:\CallRadar
git add -A
git commit -m "v100 rebuild with late-cancel guard"
git log --oneline -1
certutil -hashfile "C:\CallRadar\_releases\v100\CallRadar-v100-3.0.0-onestore.apk" MD5 | findstr /v ":"
