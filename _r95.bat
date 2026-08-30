@echo off
set L=C:\CallRadar\_r95.log
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
echo START > %L%
cd /d C:\CallRadar

echo ---COMMIT--- >> %L%
if exist .git\index.lock del /f /q .git\index.lock
git add app/build.gradle.kts >> %L% 2>&1
git commit -m "v95 / 2.9.5 release" >> %L% 2>&1

echo ---BUILD ONESTORE--- >> %L%
call gradlew.bat :app:assembleOnestoreRelease >> %L% 2>&1
echo OS_RC %ERRORLEVEL% >> %L%

echo ---BUILD PLAY BUNDLE--- >> %L%
call gradlew.bat :app:bundlePlayRelease >> %L% 2>&1
echo PLAY_RC %ERRORLEVEL% >> %L%

echo ---COLLECT--- >> %L%
mkdir C:\CallRadar\_releases\v95 2>nul
copy /y C:\CallRadar\app\build\outputs\apk\onestore\release\*.apk C:\CallRadar\_releases\v95\ >> %L% 2>&1
copy /y C:\CallRadar\app\build\outputs\mapping\onestoreRelease\mapping.txt C:\CallRadar\_releases\v95\mapping-v95-2.9.5-onestore.txt >> %L% 2>&1
copy /y C:\CallRadar\app\build\outputs\bundle\playRelease\*.aab C:\CallRadar\_releases\v95\ >> %L% 2>&1
copy /y C:\CallRadar\app\build\outputs\mapping\playRelease\mapping.txt C:\CallRadar\_releases\v95\mapping-v95-2.9.5-play.txt >> %L% 2>&1

echo ---RESULT--- >> %L%
dir C:\CallRadar\_releases\v95 >> %L% 2>&1
echo DONE >> %L%
