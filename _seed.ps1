$ErrorActionPreference='SilentlyContinue'
$base='https://callradar-server.onrender.com'
$uid=308
$days=15; $perDay=10
$eF=0;$eCash=0;$eCard=0;$eExp=0;$eTrips=0
for($d=0;$d -lt $days;$d++){
  $date=(Get-Date).AddDays(-$d).ToString('yyyy-MM-dd')
  for($t=0;$t -lt $perDay;$t++){
    $idx=$d*$perDay+$t
    $fare=7000+($idx%15)*700
    if($idx%3 -eq 0){$pay='card'}else{$pay='cash'}
    $b=@{user_id=$uid;platform='taxi';originName='';destName=("t"+$idx);fare=$fare;payment_type=$pay;source='manual';started_at=($date+'T13:00:00')}|ConvertTo-Json
    try{Invoke-WebRequest -Uri "$base/api/trips/manual" -Method POST -ContentType 'application/json' -Body $b -TimeoutSec 30 -UseBasicParsing|Out-Null}catch{}
    $eF+=$fare;$eTrips++; if($pay -eq 'card'){$eCard+=$fare}else{$eCash+=$fare}
  }
  $b2=@{user_id=$uid;category='LPG';amount=30000;expense_type='business';expense_date=$date}|ConvertTo-Json
  try{Invoke-WebRequest -Uri "$base/api/expenses" -Method POST -ContentType 'application/json' -Body $b2 -TimeoutSec 30 -UseBasicParsing|Out-Null}catch{}
  $eExp+=30000
}
Write-Output "EXPECTED trips=$eTrips fare=$eF cash=$eCash card=$eCard expense=$eExp"
try{
  $s=Invoke-WebRequest -Uri "$base/api/stats/daily/$uid" -TimeoutSec 60 -UseBasicParsing
  $daily=$s.Content|ConvertFrom-Json
  $aF=0;$aExp=0;$aTrips=0;$nd=0
  foreach($r in $daily){ $aF+=[int]$r.total_fare; $aExp+=[int]$r.expense; $aTrips+=[int]$r.trip_count; $nd++ }
  Write-Output "ACTUAL(daily) days=$nd trips=$aTrips fare=$aF expense=$aExp"
}catch{ Write-Output ("DAILY ERR "+$_.Exception.Message) }
