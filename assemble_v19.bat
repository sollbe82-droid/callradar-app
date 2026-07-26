@echo off
chcp 65001 >nul
cd /d C:\CallRadar
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
echo APK_BUILD_STARTED > apk_build_log.txt
call gradlew.bat assembleRelease >> apk_build_log.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> apk_build_log.txt
echo APK_DONE >> apk_build_log.txt
