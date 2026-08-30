$b="https://callradar-server.onrender.com"
foreach ($e in @("/api/events?days=14","/api/knowhow")) {
  try {
    $c=(Invoke-WebRequest -Uri ($b+$e) -UseBasicParsing -TimeoutSec 90).Content
    [System.IO.File]::WriteAllText("C:\CallRadar\_ev"+($e -replace '[^a-z]','')+".json",$c,[System.Text.Encoding]::UTF8)
    Write-Output ("OK "+$e+"  len="+$c.Length)
  } catch { Write-Output ("ERR "+$e+" "+$_.Exception.Message) }
}
