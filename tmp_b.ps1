Set-Location C:\CallRadar
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
& .\gradlew.bat :app:compileOnestoreDebugKotlin :app:compilePlayDebugKotlin --console=plain *> C:\CallRadar\tmp_b.log
Get-Content C:\CallRadar\tmp_b.log | Select-String -Pattern "^e:|error:|BUILD " | Select-Object -First 15
