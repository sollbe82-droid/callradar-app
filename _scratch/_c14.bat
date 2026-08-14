@echo off
echo START> C:\CallRadar\c14.log
git -C C:\CallRadar add CLAUDE.md >> C:\CallRadar\c14.log 2>&1
git -C C:\CallRadar commit -m "docs: v53 status (stages 1-4b done+committed, server b3a7227 live, 4-c tab renewal deferred to v54), archived to _releases/v53" >> C:\CallRadar\c14.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c14.log 2>&1
