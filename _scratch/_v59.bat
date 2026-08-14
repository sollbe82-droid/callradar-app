@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar
echo === COMMIT === > C:\CallRadar\v59.log
git -C C:\CallRadar add app/build.gradle.kts app/src/main/java/com/callradar/app/screen/HomeScreen.kt app/src/main/java/com/callradar/app/WorkSessionService.kt >> C:\CallRadar\v59.log 2>&1
git -C C:\CallRadar commit -m "v59/2.5.9: fix work-session pause (20s two-phone pull no longer overrides fresh local pause) + distance-inflation guard (speed 40m/s, single-hop 3km)" >> C:\CallRadar\v59.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\v59.log 2>&1
echo === BUILD START === >> C:\CallRadar\v59.log
call gradlew.bat :app:assembleOnestoreRelease >> C:\CallRadar\v59.log 2>&1
echo === DONE === >> C:\CallRadar\v59.log
