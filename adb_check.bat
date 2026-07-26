@echo off
chcp 65001 >nul
set "ADB=C:\AndroidSdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
echo ADB=%ADB% > adb_check_log.txt
"%ADB%" devices >> adb_check_log.txt 2>&1
echo DONE >> adb_check_log.txt
