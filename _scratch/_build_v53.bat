@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d C:\CallRadar
echo BUILD_START %DATE% %TIME%> build_v53.log
call gradlew.bat :app:assembleOnestoreRelease >> build_v53.log 2>&1
echo BUILD_EXITCODE=%ERRORLEVEL%>> build_v53.log
echo BUILD_END %DATE% %TIME%>> build_v53.log
