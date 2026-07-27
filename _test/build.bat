@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d C:\CallRadar
call gradlew.bat :app:assembleDebug > C:\CallRadar\_test\build.log 2>&1
echo BUILD_EXITCODE=%errorlevel%
