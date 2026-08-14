@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
echo === SERVER === > C:\CallRadar\v58.log
git -C C:\CallRadar\server add index.js >> C:\CallRadar\v58.log 2>&1
git -C C:\CallRadar\server commit -m "v58: stats/daily total/card/cash include tip+promo + bonus field (match /api/today)" >> C:\CallRadar\v58.log 2>&1
git -C C:\CallRadar\server push >> C:\CallRadar\v58.log 2>&1
echo === APP COMMIT === >> C:\CallRadar\v58.log
git -C C:\CallRadar add app/build.gradle.kts app/src/main/java/com/callradar/app/screen/RecordsScreen.kt >> C:\CallRadar\v58.log 2>&1
git -C C:\CallRadar commit -m "v58/2.5.8: records revenue=fare+tip+promo (match home); separate bonus(promo/call-fee) display" >> C:\CallRadar\v58.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\v58.log 2>&1
echo === BUILD === >> C:\CallRadar\v58.log
cd /d C:\CallRadar
call gradlew.bat :app:assembleOnestoreRelease >> C:\CallRadar\v58.log 2>&1
echo === DONE === >> C:\CallRadar\v58.log
