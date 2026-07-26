@echo off
chcp 65001 >nul
cd /d C:\CallRadar
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jre"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [!] Android Studio JDK not found at expected path.
  echo     Open Android Studio, or tell Claude, and we'll fix the path.
  pause
  exit /b 1
)
echo Using JAVA_HOME=%JAVA_HOME%
echo Building v16 signed release AAB ... (this can take a few minutes)
echo.
call gradlew.bat bundleRelease
echo.
echo ============================================================
echo If "BUILD SUCCESSFUL" is shown above:
echo   AAB = C:\CallRadar\app\build\outputs\bundle\release\app-release.aab
echo   -^> Upload that file to Play Console as v16 (versionCode 16).
echo.
echo If it FAILED (BUILD FAILED / red text):
echo   Screenshot the error lines and send them to Claude.
echo ============================================================
pause
