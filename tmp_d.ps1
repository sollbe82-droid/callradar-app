Set-Location C:\CallRadar\server
& git add -A 2>&1 | Out-Null
& git commit -m "v94: auto-record (accessibility) terms page for prominent disclosure" 2>&1 | Select-String -Pattern "files changed"
& git push origin main 2>&1 | Select-String -Pattern "main ->"
Set-Location C:\CallRadar
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
& .\gradlew.bat :app:installOnestoreDebug --console=plain *> C:\CallRadar\tmp_d.log
Get-Content C:\CallRadar\tmp_d.log | Select-String -Pattern "Installed|BUILD |DeviceException" | Select-Object -Last 3
& git add app 2>&1 | Out-Null
& git commit -m "v94: prominent disclosure + consent gate for accessibility auto-record; always-available terms list in More" 2>&1 | Select-String -Pattern "files changed"
