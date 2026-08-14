@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar
echo === COMMIT === > C:\CallRadar\v57.log
git -C C:\CallRadar add app/build.gradle.kts app/src/main/java/com/callradar/app/NaviIntentReceiver.kt app/src/main/java/com/callradar/app/screen/HomeScreen.kt >> C:\CallRadar\v57.log 2>&1
git -C C:\CallRadar commit -m "v57/2.5.7: toggle UI merged into one card; toll->expense auto-split (meter-only revenue); midnight date attribution (day_start_hour opt-in, regression-safe)" >> C:\CallRadar\v57.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\v57.log 2>&1
echo === BUILD START === >> C:\CallRadar\v57.log
call gradlew.bat :app:assembleOnestoreRelease >> C:\CallRadar\v57.log 2>&1
echo === DONE === >> C:\CallRadar\v57.log
