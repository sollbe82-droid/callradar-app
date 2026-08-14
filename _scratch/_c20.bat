@echo off
echo START> C:\CallRadar\c20.log
git -C C:\CallRadar add app/src >> C:\CallRadar\c20.log 2>&1
git -C C:\CallRadar commit -m "v54 4: my-location marker on dedicated label layer (immune to hotzone removeAll)" >> C:\CallRadar\c20.log 2>&1
git -C C:\CallRadar log --oneline -1 >> C:\CallRadar\c20.log 2>&1
