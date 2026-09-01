cd /d C:\CallRadar
git add -A
git commit -m "google approved accessibility (alpha 9/1); production v100 promoted 9/2"
git log --oneline -1
cd /d C:\CallRadar\server
git add -A
git commit -m "anomalies: version_adoption - is the new build actually in use"
git push
git log --oneline -1
