# AAB manifest check without linux tools.
# Reads base/manifest/AndroidManifest.xml (binary AXML) and searches the string pool
# for UTF-16LE tokens. A control token is checked too, so a silent failure of the
# search method itself is visible (CLAUDE.md: always check a control).
param([string]$Aab = "C:\CallRadar\app\build\outputs\bundle\playRelease\app-play-release.aab")

Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not (Test-Path $Aab)) { Write-Output "AAB_NOT_FOUND $Aab"; exit 1 }

$zip = [System.IO.Compression.ZipFile]::OpenRead($Aab)
$entry = $zip.Entries | Where-Object { $_.FullName -eq 'base/manifest/AndroidManifest.xml' }
if (-not $entry) { Write-Output "MANIFEST_ENTRY_MISSING"; $zip.Dispose(); exit 1 }

$ms = New-Object System.IO.MemoryStream
$entry.Open().CopyTo($ms)
$bytes = $ms.ToArray()
$zip.Dispose()
Write-Output ("MANIFEST_BYTES " + $bytes.Length)

# AXML string pool is UTF-16LE; also try UTF-8 in case of a compact pool.
$u16 = [System.Text.Encoding]::Unicode.GetString($bytes)
$u8  = [System.Text.Encoding]::UTF8.GetString($bytes)

function Has([string]$needle) {
  if ($u16.Contains($needle)) { return $true }
  if ($u8.Contains($needle))  { return $true }
  return $false
}

# Controls first — if these are false, the search method itself is broken.
Write-Output ("CONTROL_package        " + (Has 'com.callradar.app'))
Write-Output ("CONTROL_NaviIntent     " + (Has 'NaviIntentReceiver'))
# The two that decide whether we may upload.
Write-Output ("BIND_ACCESSIBILITY     " + (Has 'BIND_ACCESSIBILITY_SERVICE'))
Write-Output ("isAccessibilityTool    " + (Has 'isAccessibilityTool'))
