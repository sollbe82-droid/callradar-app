$b="https://callradar-server.onrender.com"
foreach ($i in 1,163) {
  $c=(Invoke-WebRequest -Uri "$b/api/trips/$i`?limit=1000&cb=$i" -UseBasicParsing -TimeoutSec 90).Content
  [System.IO.File]::WriteAllText("C:\CallRadar\_chk_$i.json",$c,[System.Text.Encoding]::UTF8)
  Write-Output "$i len=$($c.Length)"
}
