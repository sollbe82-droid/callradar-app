@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d C:\CallRadar
call gradlew.bat :app:assembleRelease > C:\CallRadar\_test\buildrel.log 2>&1
echo BUILD_EXITCODE=%errorlevel%
