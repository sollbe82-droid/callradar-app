@echo off
cd /d C:\CallRadar
del /f /q ".git\index.lock" 2>nul
echo START> git_commit.log
git add app/src app/build.gradle.kts CLAUDE.md >> git_commit.log 2>&1
git commit -m "v51 (2.5.1): bump versionCode 51 + uber idle-home guard tighten (안전도구키트) + radar nav TODO note" >> git_commit.log 2>&1
git log --oneline -3 >> git_commit.log 2>&1
