@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\CallRadar
echo === COMMIT === > C:\CallRadar\v56.log
git -C C:\CallRadar add app/build.gradle.kts app/src/main/java/com/callradar/app/NaviIntentReceiver.kt app/src/main/java/com/callradar/app/TmoneyNotificationService.kt app/src/main/java/com/callradar/app/screen/HomeScreen.kt >> C:\CallRadar\v56.log 2>&1
git -C C:\CallRadar commit -m "v56/2.5.6: fix auto-record toggle OFF (auto_free_open no longer overrides once user toggles; auto_record_touched), gate TmoneyNotificationService on notif_capture_on (amount-capture toggle now fully works)" >> C:\CallRadar\v56.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\v56.log 2>&1
echo === BUILD START === >> C:\CallRadar\v56.log
call gradlew.bat :app:assembleOnestoreRelease >> C:\CallRadar\v56.log 2>&1
echo === DONE === >> C:\CallRadar\v56.log
