@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar
git add -A
git commit -m "v104: show the basis under hourly rate so the driver can check it (user 108)"
call gradlew.bat assembleOnestoreRelease > C:\CallRadar\_build104.log 2>&1
