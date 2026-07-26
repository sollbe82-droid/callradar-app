param([string]$Text, [string]$Shot = "")
$ErrorActionPreference = "SilentlyContinue"
$adb = "C:\AndroidSdk\platform-tools\adb.exe"
& $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
& $adb pull /sdcard/ui.xml C:\CallRadar\_test\ui.xml | Out-Null
[xml]$xml = Get-Content C:\CallRadar\_test\ui.xml -Encoding UTF8
$hit = $null
foreach ($n in $xml.SelectNodes("//node")) {
    $t = "$($n.text)"; $d = "$($n.'content-desc')"
    if ($t -like "*$Text*" -or $d -like "*$Text*") { $hit = $n; break }
}
if ($hit -and $hit.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $cx = [int](([int]$matches[1] + [int]$matches[3]) / 2)
    $cy = [int](([int]$matches[2] + [int]$matches[4]) / 2)
    & $adb shell input tap $cx $cy
    Write-Output "TAPPED '$Text' @ $cx,$cy"
} else {
    Write-Output "NOT_FOUND '$Text'"
}
Start-Sleep 2
if ($Shot -ne "") {
    & $adb shell screencap -p /sdcard/s.png | Out-Null
    & $adb pull /sdcard/s.png "C:\CallRadar\_test\$Shot" | Out-Null
}
