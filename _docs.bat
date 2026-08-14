@echo off
git -C C:\CallRadar add CLAUDE.md HANDOVER.md > C:\CallRadar\_scratch\docs.log 2>&1
git -C C:\CallRadar commit -m "docs: compress CLAUDE.md (rules kept, history condensed) + HANDOVER 2026-08-13 (v55-v59 summary, open items) for new session" >> C:\CallRadar\_scratch\docs.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\_scratch\docs.log 2>&1
