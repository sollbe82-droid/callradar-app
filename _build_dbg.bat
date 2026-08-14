@echo off
cd /d C:\CallRadar
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
echo DBG_START %DATE% %TIME% > C:\CallRadar\_build_dbg.log
call gradlew.bat :app:assembleOnestoreDebug >> C:\CallRadar\_build_dbg.log 2>&1
echo DBG_EXITCODE %ERRORLEVEL% >> C:\CallRadar\_build_dbg.log
echo DBG_DONE %DATE% %TIME% >> C:\CallRadar\_build_dbg.log
