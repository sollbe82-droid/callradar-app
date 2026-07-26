@echo off
cd /d C:\CallRadar\_test
node autobatch2.js 25 fast > batch_fast_log.txt 2>&1
echo BATCH_DONE >> batch_fast_log.txt
