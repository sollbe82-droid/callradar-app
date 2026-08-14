@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar
echo === COMMIT === > C:\CallRadar\v56b.log
git -C C:\CallRadar add app/src/main/java/com/callradar/app/screen/HomeScreen.kt >> C:\CallRadar\v56b.log 2>&1
git -C C:\CallRadar commit -m "v56: work-session summary server save on shift-end (client push to /api/work-session/close)" >> C:\CallRadar\v56b.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\v56b.log 2>&1
echo === BUILD START === >> C:\CallRadar\v56b.log
call gradlew.bat :app:assembleOnestoreRelease >> C:\CallRadar\v56b.log 2>&1
echo === DONE === >> C:\CallRadar\v56b.log
