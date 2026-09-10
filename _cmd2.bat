@echo off
cd /d C:\CallRadar
git add -A
git commit -m "v111 release artifacts + handover: onestore APK, play AAB, mappings, aab manifest check"
git log --oneline -1
echo FINAL_COMMIT_DONE
