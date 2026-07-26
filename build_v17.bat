@echo off
chcp 65001 >nul
cd /d C:\CallRadar
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jre"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo JAVA_HOME_NOT_FOUND > build_v17_log.txt
  echo BUILD_DONE >> build_v17_log.txt
  exit /b 1
)
echo BUILD_STARTED > build_v17_log.txt
echo Using JAVA_HOME=%JAVA_HOME% >> build_v17_log.txt
call gradlew.bat bundleRelease >> build_v17_log.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> build_v17_log.txt
echo BUILD_DONE >> build_v17_log.txt
