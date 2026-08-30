@echo off
title CallRadar Runner 2
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d C:\CallRadar
echo ================================================
echo  CallRadar Runner 2 - keep this window OPEN.
echo  Close this window to stop.
echo ================================================
:loop
echo alive2 > C:\CallRadar\_alive2.txt
if exist C:\CallRadar\_cmd2.bat call :runit
ping -n 4 127.0.0.1 >nul
goto loop

:runit
echo [RUN2] started
set "STAMP=%RANDOM%%RANDOM%"
call C:\CallRadar\_cmd2.bat > C:\CallRadar\_out_%STAMP%.log 2>&1
copy /y C:\CallRadar\_out_%STAMP%.log C:\CallRadar\_cmd2.log >nul
del /f /q C:\CallRadar\_out_%STAMP%.log
del /f /q C:\CallRadar\_cmd2.bat
echo RUN2_DONE >> C:\CallRadar\_cmd2.log
echo [RUN2] done
goto :eof
