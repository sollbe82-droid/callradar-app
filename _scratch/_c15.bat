@echo off
echo START> C:\CallRadar\c15.log
git -C C:\CallRadar add app/src app/build.gradle.kts >> C:\CallRadar\c15.log 2>&1
git -C C:\CallRadar commit -m "v54 4-c-1: absorb salary(wolgeup) tab into wolbyeol top summary card; records tabs now [naeyeok, wolbyeol, jichul]; bump versionCode 54" >> C:\CallRadar\c15.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c15.log 2>&1
