@echo off
chcp 65001 >nul
cd /d C:\CallRadar
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jre"
del /q build_v16_auto.log 2>nul
echo BUILD_START %DATE% %TIME% > build_v16_auto.log
echo JAVA_HOME=%JAVA_HOME% >> build_v16_auto.log
call gradlew.bat bundleRelease >> build_v16_auto.log 2>&1
echo EXITCODE=%ERRORLEVEL% >> build_v16_auto.log
echo BUILD_END %DATE% %TIME% >> build_v16_auto.log
