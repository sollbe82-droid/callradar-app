$b="https://callradar-server.onrender.com"
foreach ($e in @("/api/stats2/108","/api/rhythm/108","/api/stats/insights/108")) {
  try {
    $c=(Invoke-WebRequest -Uri ($b+$e+"?cb="+(Get-Random)) -UseBasicParsing -TimeoutSec 90).Content
    Write-Output ("### "+$e)
    Write-Output $c.Substring(0,[Math]::Min(700,$c.Length))
  } catch { Write-Output ("### "+$e+"  ERR "+$_.Exception.Message) }
}
