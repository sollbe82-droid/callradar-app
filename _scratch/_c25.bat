@echo off
echo START> C:\CallRadar\c25.log
git -C C:\CallRadar add CLAUDE.md >> C:\CallRadar\c25.log 2>&1
git -C C:\CallRadar commit -m "docs: v54 final (learning-count card, LPG liters 3-decimal parse, HERO revert), next task = radar regional classification" >> C:\CallRadar\c25.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c25.log 2>&1
