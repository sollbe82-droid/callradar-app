# 전체 무료개방 토글. 키는 파일에 적지 않는다.
$k = $env:CR_ADMIN_KEY
if (-not $k) { $k = Read-Host -Prompt 'ADMIN_KEY' }
$b = 'https://callradar-server.onrender.com'
$H = @{ 'x-admin-key' = $k }        # ?key= 는 2026-08-27 부터 403 (위치정보법 고시 제8조)
$r = Invoke-RestMethod -Uri "$b/api/admin/free-open" -Method Post -Headers $H -ContentType 'application/json' -Body (@{on=$true}|ConvertTo-Json)
Write-Output ('free-open=' + ($r|ConvertTo-Json -Compress))
Start-Sleep -Seconds 1
$f = Invoke-RestMethod -Uri "$b/api/users/999999/flags"
Write-Output ('flags(random)=' + ($f|ConvertTo-Json -Compress))
