@echo off
set L=C:\CallRadar\_r96.log
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set ADB=C:\AndroidSdk\platform-tools\adb.exe
echo START > %L%
cd /d C:\CallRadar

echo ---DEBUG COMPILE CHECK--- >> %L%
call gradlew.bat :app:assembleOnestoreDebug >> %L% 2>&1
echo DBG_RC %ERRORLEVEL% >> %L%
if not %ERRORLEVEL%==0 goto :end

echo ---INSTALL DEBUG--- >> %L%
%ADB% install -r C:\CallRadar\app\build\outputs\apk\onestore\debug\app-onestore-debug.apk >> %L% 2>&1

echo ---COMMIT--- >> %L%
if exist .git\index.lock del /f /q .git\index.lock
git add -A app >> %L% 2>&1
git commit -m "v96: receipt parser priority fix + cross-app auto-record break (user 103)" >> %L% 2>&1

echo ---RELEASE ONESTORE--- >> %L%
call gradlew.bat :app:assembleOnestoreRelease >> %L% 2>&1
echo OS_RC %ERRORLEVEL% >> %L%

echo ---RELEASE PLAY--- >> %L%
call gradlew.bat :app:bundlePlayRelease >> %L% 2>&1
echo PLAY_RC %ERRORLEVEL% >> %L%

echo ---COLLECT--- >> %L%
mkdir C:\CallRadar\_releases\v96 2>nul
copy /y C:\CallRadar\app\build\outputs\apk\onestore\release\*.apk C:\CallRadar\_releases\v96\ >> %L% 2>&1
copy /y C:\CallRadar\app\build\outputs\mapping\onestoreRelease\mapping.txt C:\CallRadar\_releases\v96\mapping-v96-2.9.6-onestore.txt >> %L% 2>&1
copy /y C:\CallRadar\app\build\outputs\bundle\playRelease\*.aab C:\CallRadar\_releases\v96\ >> %L% 2>&1
copy /y C:\CallRadar\app\build\outputs\mapping\playRelease\mapping.txt C:\CallRadar\_releases\v96\mapping-v96-2.9.6-play.txt >> %L% 2>&1
dir C:\CallRadar\_releases\v96 >> %L% 2>&1

:end
echo DONE >> %L%
