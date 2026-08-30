$ids = 592,163,108,103
foreach ($i in $ids) {
  try {
    $u = "https://callradar-server.onrender.com/api/trips/$i" + "?limit=1000&cb=$i"
    $c = (Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 90).Content
    [System.IO.File]::WriteAllText("C:\CallRadar\_tr_$i.json", $c, [System.Text.Encoding]::UTF8)
    Write-Output "$i ok $($c.Length)"
  } catch { Write-Output "$i ERR $($_.Exception.Message)" }
}
