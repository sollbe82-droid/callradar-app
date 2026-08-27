# 유저 198 에게 권한 부여. 키는 파일에 적지 않는다.
#  실행 전:  $env:CR_ADMIN_KEY = '<키>'      (또는 아래 프롬프트에 입력)
$k = $env:CR_ADMIN_KEY
if (-not $k) { $k = Read-Host -Prompt 'ADMIN_KEY' }
$b = 'https://callradar-server.onrender.com'
$H = @{ 'x-admin-key' = $k }        # ?key= 는 2026-08-27 부터 403 (위치정보법 고시 제8조)
$r1 = Invoke-RestMethod -Uri "$b/api/admin/entitle" -Method Post -Headers $H -ContentType 'application/json' -Body (@{target_user_id=198;on=$true}|ConvertTo-Json)
$r2 = Invoke-RestMethod -Uri "$b/api/admin/claim"   -Method Post -Headers $H -ContentType 'application/json' -Body (@{user_id=198}|ConvertTo-Json)
$f  = Invoke-RestMethod -Uri "$b/api/users/198/flags"
Write-Output ('entitle=' + ($r1|ConvertTo-Json -Compress))
Write-Output ('claim='   + ($r2|ConvertTo-Json -Compress))
Write-Output ('flags='   + ($f |ConvertTo-Json -Compress))
