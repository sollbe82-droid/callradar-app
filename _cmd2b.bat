@echo off
chcp 65001 >nul
powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; try { $r=Invoke-RestMethod -Uri 'https://callradar-server.onrender.com/api/airport/diag?probe=1' -TimeoutSec 150; $r|ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 C:\CallRadar\_probe.json } catch { $_.Exception.Message | Out-File -Encoding utf8 C:\CallRadar\_probe.json }"
echo PROBE_DONE >> C:\CallRadar\_probechk.log
