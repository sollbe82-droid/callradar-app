@echo off
chcp 65001 >nul
set "ADB=C:\AndroidSdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
"%ADB%" shell pkill -INT screenrecord >> C:\CallRadar\rec_log.txt 2>&1
ping -n 4 127.0.0.1 >nul
"%ADB%" pull /sdcard/callradar_demo.mp4 C:\CallRadar\callradar_demo.mp4 >> C:\CallRadar\rec_log.txt 2>&1
echo PULLED >> C:\CallRadar\rec_log.txt
