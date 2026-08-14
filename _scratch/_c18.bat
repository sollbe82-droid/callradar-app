@echo off
echo START> C:\CallRadar\c18.log
git -C C:\CallRadar add CLAUDE.md >> C:\CallRadar\c18.log 2>&1
git -C C:\CallRadar commit -m "docs: v54 status (wolgeup tab removed, 900km distance fix, dedup take-home), remaining feedback items, archived _releases/v54" >> C:\CallRadar\c18.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c18.log 2>&1
