@echo off
git -C C:\CallRadar add CLAUDE.md > C:\CallRadar\cdoc.log 2>&1
git -C C:\CallRadar commit -m "docs: v55 shipped (fare-misparse guard + call-cancel floating fix), v56 plan (toll->expense, midnight date attribution)" >> C:\CallRadar\cdoc.log 2>&1
git -C C:\CallRadar log --oneline -2 >> C:\CallRadar\cdoc.log 2>&1
