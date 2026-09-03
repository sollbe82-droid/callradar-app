@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar\server
git add -A
git commit -m "usage/audit: per_user + return rate (stop hand-computing come-once-never-again)"
git push
cd /d C:\CallRadar
git add -A
git commit -m "telemetry on 14 activities (insights had zero rows for 60 days - not unused, unmeasured) + home brief shown"
git log --oneline -1
