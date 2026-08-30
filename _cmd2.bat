@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar\server
git add -A
git commit -m "home-brief: personal pace + best remaining band (replaces demand/events line)"
git push
cd /d C:\CallRadar
git add -A
git commit -m "v100 home brief card: server-composed personal lines"
echo SERVER_PUSHED > C:\CallRadar\_step1.txt
call gradlew.bat assembleOnestoreRelease > C:\CallRadar\_build100b.log 2>&1
echo BUILD_DONE > C:\CallRadar\_step2.txt
