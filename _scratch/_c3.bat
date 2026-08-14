@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START> C:\CallRadar\c3.log
git add app/proguard-rules.pro app/build.gradle.kts >> C:\CallRadar\c3.log 2>&1
git commit -m "v51: enable R8 obfuscation (minify + keep rules for kakao/mlkit/gms/okhttp)" >> C:\CallRadar\c3.log 2>&1
echo EXIT=%ERRORLEVEL%>> C:\CallRadar\c3.log
git log --oneline -3 >> C:\CallRadar\c3.log 2>&1
