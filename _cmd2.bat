@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar\server
git add -A
git commit -m "fare-segments: hourly rate must exclude paused time (user 108)"
git push
cd /d C:\CallRadar
git add -A
git commit -m "v102: hourly rate from work segments (pause excluded) + accessibility disconnect telemetry"
call gradlew.bat assembleOnestoreRelease > C:\CallRadar\_build102.log 2>&1
