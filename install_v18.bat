@echo off
chcp 65001 >nul
cd /d C:\CallRadar
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jre"
echo INSTALL_STARTED > install_v18_log.txt
call gradlew.bat installRelease >> install_v18_log.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> install_v18_log.txt
echo INSTALL_DONE >> install_v18_log.txt
