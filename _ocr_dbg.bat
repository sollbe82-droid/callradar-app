@echo off
chcp 65001 >nul
cd /d C:\CallRadar
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
echo OCR_DBG_START %DATE% %TIME% > C:\CallRadar\_ocr_dbg.log
call gradlew.bat :app:assembleOnestoreDebug >> C:\CallRadar\_ocr_dbg.log 2>&1
echo BUILD_EXITCODE %ERRORLEVEL% >> C:\CallRadar\_ocr_dbg.log
echo. >> C:\CallRadar\_ocr_dbg.log
echo ---- ADB INSTALL ---- >> C:\CallRadar\_ocr_dbg.log
C:\AndroidSdk\platform-tools\adb.exe devices >> C:\CallRadar\_ocr_dbg.log 2>&1
C:\AndroidSdk\platform-tools\adb.exe install -r C:\CallRadar\app\build\outputs\apk\onestore\debug\app-onestore-debug.apk >> C:\CallRadar\_ocr_dbg.log 2>&1
echo INSTALL_EXITCODE %ERRORLEVEL% >> C:\CallRadar\_ocr_dbg.log
echo OCR_DBG_DONE %DATE% %TIME% >> C:\CallRadar\_ocr_dbg.log
