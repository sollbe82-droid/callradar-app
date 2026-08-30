@echo off
title CallRadar Runner
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d C:\CallRadar
echo ================================================
echo  CallRadar Runner - keep this window OPEN.
echo  Close this window to stop.
echo ================================================
:loop
echo alive > C:\CallRadar\_alive.txt
if exist C:\CallRadar\_cmd.bat call :runit
ping -n 4 127.0.0.1 >nul
goto loop

:runit
echo [RUN] started
echo RUN_START > C:\CallRadar\_cmd.log
call C:\CallRadar\_cmd.bat >> C:\CallRadar\_cmd.log 2>&1
echo RUN_EXITCODE %ERRORLEVEL% >> C:\CallRadar\_cmd.log
echo RUN_DONE >> C:\CallRadar\_cmd.log
del /f /q C:\CallRadar\_cmd.bat
echo [RUN] done
goto :eof
