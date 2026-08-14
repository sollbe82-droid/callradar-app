@echo off
echo START> C:\CallRadar\c22.log
git -C C:\CallRadar add CLAUDE.md >> C:\CallRadar\c22.log 2>&1
git -C C:\CallRadar commit -m "docs: v54 marker + region-aware guidance done/verified; remaining = home reorg (refactor) + low-pri learning/OCR" >> C:\CallRadar\c22.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c22.log 2>&1
