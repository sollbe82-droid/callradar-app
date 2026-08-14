@echo off
set ADB=C:\AndroidSdk\platform-tools\adb.exe
set APK=C:\CallRadar\app\build\outputs\apk\release\app-release.apk
"%ADB%" devices > C:\CallRadar\_test\install_check.log 2>&1
"%ADB%" install -r "%APK%" >> C:\CallRadar\_test\install_check.log 2>&1
echo INSTALL_EXITCODE=%errorlevel% >> C:\CallRadar\_test\install_check.log
"%ADB%" shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1 >> C:\CallRadar\_test\install_check.log 2>&1
