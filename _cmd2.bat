@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar
git add -A
git commit -m "v103: tell the driver when accessibility is off - honest label, simple-mode detection, in-shift notification"
call gradlew.bat assembleOnestoreRelease > C:\CallRadar\_build103.log 2>&1
