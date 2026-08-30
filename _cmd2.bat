@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar\server
git add -A
git commit -m "anomalies: late_cancel shows KST deletion time"
git push
cd /d C:\CallRadar
git add -A
git commit -m "do not delete a trip as call-cancel after 10min (90.7 pct of real cancels happen within 10min, n=872)"
call gradlew.bat assembleOnestoreRelease > C:\CallRadar\_build100c.log 2>&1
echo BUILT > C:\CallRadar\_bstep.txt
