@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar
git add -A
git commit -m "v111 3.1.1: single BusinessDay for business-day boundary (was 5 copies), shared numerator for hourly/km rates, app self-checks its own numbers"
echo COMMIT_OK
call gradlew.bat --no-daemon assembleOnestoreRelease > C:\CallRadar\_rel_one.log 2>&1
findstr /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" C:\CallRadar\_rel_one.log
echo ONESTORE_DONE
call gradlew.bat --no-daemon bundlePlayRelease > C:\CallRadar\_rel_play.log 2>&1
findstr /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" C:\CallRadar\_rel_play.log
echo PLAY_DONE
