@echo off
cd /d C:\CallRadar
del /f /q C:\CallRadar\_test\release.log 2>nul
where java >nul 2>&1
if errorlevel 1 if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call gradlew.bat assembleRelease --console=plain > C:\CallRadar\_test\release.log 2>&1
echo RELEASE_EXIT=%errorlevel% >> C:\CallRadar\_test\release.log
dir /s /b C:\CallRadar\app\build\outputs\apk\release\*.apk >> C:\CallRadar\_test\release.log 2>&1
dir /s /b C:\CallRadar\app\build\outputs\bundle\*.aab >> C:\CallRadar\_test\release.log 2>&1
