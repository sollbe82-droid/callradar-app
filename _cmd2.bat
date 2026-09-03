@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar\server
git add -A
git commit -m "day-start-hint: recommend the quiet hour from the driver's own 8-week history"
git push
cd /d C:\CallRadar
git add -A
git commit -m "v101: rest-checkbox fix, day-start guidance rewrite + data-driven hint, insights telemetry, brief tap-through"
call gradlew.bat assembleOnestoreRelease > C:\CallRadar\_build101.log 2>&1
