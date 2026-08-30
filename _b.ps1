$b="https://callradar-server.onrender.com"
foreach ($i in 592,108) {
  $c=(Invoke-WebRequest -Uri "$b/api/trips/$i`?limit=300&cb=$(Get-Random)" -UseBasicParsing -TimeoutSec 90).Content
  [System.IO.File]::WriteAllText("C:\CallRadar\_bug_$i.json",$c,[System.Text.Encoding]::UTF8)
  Write-Output "$i ok"
}
