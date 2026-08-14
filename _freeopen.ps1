$k='90d96600658fbf204a3032a69455e8b8'
$b='https://callradar-server.onrender.com'
$r=Invoke-RestMethod -Uri "$b/api/admin/free-open" -Method Post -ContentType 'application/json' -Body (@{on=$true;key=$k}|ConvertTo-Json)
Write-Output ('free-open=' + ($r|ConvertTo-Json -Compress))
Start-Sleep -Seconds 1
# 무권한 임의계정으로 flags 확인 (free_open 반영 여부)
$f=Invoke-RestMethod -Uri "$b/api/users/999999/flags"
Write-Output ('flags(random)=' + ($f|ConvertTo-Json -Compress))
