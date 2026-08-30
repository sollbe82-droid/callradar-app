$b="https://callradar-server.onrender.com"
foreach ($e in @("/api/health/anomalies?days=60","/api/rhythm/460","/api/stats2/460")) {
  try { $c=(Invoke-WebRequest -Uri ($b+$e+"&cb="+(Get-Random)) -UseBasicParsing -TimeoutSec 90).Content }
  catch { try { $c=(Invoke-WebRequest -Uri ($b+$e+"?cb="+(Get-Random)) -UseBasicParsing -TimeoutSec 90).Content } catch { $c="ERR "+$_.Exception.Message } }
  [System.IO.File]::WriteAllText("C:\CallRadar\_r"+($e -replace '[^a-z0-9]','')+".json",$c,[System.Text.Encoding]::UTF8)
  Write-Output ("OK "+$e)
}
