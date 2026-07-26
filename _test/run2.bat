@echo off
cd /d C:\CallRadar\_test
node autobatch2.js 2 > batch_log.txt 2>&1
echo BATCH_DONE >> batch_log.txt
