@echo off
chcp 65001 >nul
cd /d C:\CallRadar
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
echo VERIFY_START %DATE% %TIME% > C:\CallRadar\_build_verify.log
call gradlew.bat :app:compilePlayDebugKotlin --console=plain >> C:\CallRadar\_build_verify.log 2>&1
echo VERIFY_EXITCODE %ERRORLEVEL% >> C:\CallRadar\_build_verify.log
echo VERIFY_DONE %DATE% %TIME% >> C:\CallRadar\_build_verify.log
