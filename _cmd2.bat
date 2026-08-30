cd /d C:\CallRadar
git rm -q --cached _s1.txt _s2.txt _s3.txt _cl.txt _cm.txt _glok.txt _gl.txt _rmok.txt 2>nul
del /q C:\CallRadar\_s1.txt C:\CallRadar\_s2.txt C:\CallRadar\_s3.txt C:\CallRadar\_gl.txt 2>nul
git add -A
git commit -m "drop stray runner artifacts"
git log --oneline -1
echo DONE > C:\CallRadar\_cn.txt
