@echo off
chcp 65001 >nul
set "ADB=C:\AndroidSdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
echo REC_START > C:\CallRadar\rec_log.txt
"%ADB%" shell screenrecord --time-limit 180 --bit-rate 8000000 /sdcard/callradar_demo.mp4 >> C:\CallRadar\rec_log.txt 2>&1
echo REC_STOPPED >> C:\CallRadar\rec_log.txt
