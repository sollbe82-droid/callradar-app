@echo off
cd /d C:\CallRadar
set ADB=C:\AndroidSdk\platform-tools\adb.exe
"%ADB%" install -r "C:\CallRadar\app\build\outputs\apk\onestore\release\app-onestore-release.apk" > C:\CallRadar\test51.log 2>&1
"%ADB%" logcat -c
"%ADB%" shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 >> C:\CallRadar\test51.log 2>&1
ping -n 10 127.0.0.1 >nul
"%ADB%" exec-out screencap -p > C:\CallRadar\home51.png
"%ADB%" shell input tap 316 2071 >> C:\CallRadar\test51.log 2>&1
ping -n 9 127.0.0.1 >nul
"%ADB%" exec-out screencap -p > C:\CallRadar\radar51.png
"%ADB%" logcat -d -v brief > C:\CallRadar\logcat51.log 2>&1
echo DONE %DATE% %TIME%>> C:\CallRadar\test51.log
