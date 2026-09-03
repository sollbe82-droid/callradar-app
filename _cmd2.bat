@echo off
del /q C:\CallRadar\_h1.xml C:\CallRadar\_h3.xml C:\CallRadar\_h4.xml C:\CallRadar\_g1.xml C:\CallRadar\_g2.xml C:\CallRadar\_g3.xml C:\CallRadar\_g4.xml C:\CallRadar\_g5.xml C:\CallRadar\_g6.xml C:\CallRadar\_cr3.txt 2>nul
cd /d C:\CallRadar
git add -A
git commit -m "v103 both stores: release notes + store text"
git log --oneline -1
