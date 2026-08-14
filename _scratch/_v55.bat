@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar
echo === COMMIT === > C:\CallRadar\v55.log
git -C C:\CallRadar add app/build.gradle.kts app/src/main/java/com/callradar/app/NaviIntentReceiver.kt app/src/main/java/com/callradar/app/FloatingTripService.kt CLAUDE.md >> C:\CallRadar\v55.log 2>&1
git -C C:\CallRadar commit -m "v55/2.5.5: #4 fare-misparse guard (exclude uber/kakao home dashboard cumulative total), #1 floating tap=cancel-arm (call-cancel stuck/meokteong fix)" >> C:\CallRadar\v55.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\v55.log 2>&1
echo === BUILD START === >> C:\CallRadar\v55.log
call gradlew.bat :app:assembleOnestoreRelease >> C:\CallRadar\v55.log 2>&1
echo === DONE === >> C:\CallRadar\v55.log
