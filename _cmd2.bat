@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar
git checkout 5058ba6 -- app/src/main/java/com/callradar/app/screen/DayStartDialog.kt
git add -A
git commit -m "revert day-start guidance change - keep the rule as is (owner decision)"
cd /d C:\CallRadar\server
git add -A
git commit -m "revert day-start-hint endpoint"
git push
cd /d C:\CallRadar
git log --oneline -1
