$k='90d96600658fbf204a3032a69455e8b8'
$b='https://callradar-server.onrender.com'
$r1=Invoke-RestMethod -Uri "$b/api/admin/entitle" -Method Post -ContentType 'application/json' -Body (@{target_user_id=198;on=$true;key=$k}|ConvertTo-Json)
$r2=Invoke-RestMethod -Uri "$b/api/admin/claim" -Method Post -ContentType 'application/json' -Body (@{user_id=198;key=$k}|ConvertTo-Json)
$f=Invoke-RestMethod -Uri "$b/api/users/198/flags"
Write-Output ('entitle=' + ($r1|ConvertTo-Json -Compress))
Write-Output ('claim=' + ($r2|ConvertTo-Json -Compress))
Write-Output ('flags=' + ($f|ConvertTo-Json -Compress))
