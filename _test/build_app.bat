@echo off
cd /d C:\CallRadar
del /f /q C:\CallRadar\_test\build.log 2>nul
where java >nul 2>&1
if errorlevel 1 if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call gradlew.bat assembleDebug --console=plain > C:\CallRadar\_test\build.log 2>&1
echo BUILD_EXIT=%errorlevel% >> C:\CallRadar\_test\build.log
