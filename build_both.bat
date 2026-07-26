@echo off
chcp 65001 >nul
cd /d C:\CallRadar
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
echo BUILD_STARTED > build_both_log.txt
call gradlew.bat assembleRelease bundleRelease >> build_both_log.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> build_both_log.txt
echo BUILD_DONE >> build_both_log.txt
