param([string]$Dir = "C:\CallRadar\_video\short_20260908")

# ============================================================
#  이미 만들어진 v01.wav.. (음성) + s01.png.. (슬라이드) 로 영상 합성
#  음성은 tts_neural.py 가 먼저 만든다. 이 스크립트는 음성을 만들지 않는다.
# ============================================================
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$FF  = "C:\CallRadar\ffmpeg.exe"
$BGM = "C:\CallRadar\_video\bgm\bgm_brief.wav"

$json  = Get-Content (Join-Path $Dir 'lines.json') -Raw -Encoding UTF8
$lines = $json | ConvertFrom-Json

function Get-WavSeconds([string]$path) {
  $fs = [System.IO.File]::OpenRead($path)
  try {
    $br = New-Object System.IO.BinaryReader($fs)
    $br.ReadBytes(12) | Out-Null
    $byteRate = 0; $dataSize = 0
    while ($fs.Position -lt $fs.Length - 8) {
      $id = [System.Text.Encoding]::ASCII.GetString($br.ReadBytes(4))
      $sz = $br.ReadInt32()
      if ($id -eq 'fmt ') {
        $p = $fs.Position; $br.ReadBytes(8) | Out-Null
        $byteRate = $br.ReadInt32(); $fs.Position = $p + $sz
      } elseif ($id -eq 'data') { $dataSize = $sz; break }
      else { $fs.Position = $fs.Position + $sz + ($sz % 2) }
    }
    if ($byteRate -le 0) { return 4.0 }
    return [math]::Round($dataSize / $byteRate, 3)
  } finally { $fs.Close() }
}

$durs = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
  $d = Get-WavSeconds (Join-Path $Dir ("v{0:d2}.wav" -f ($i + 1)))
  $durs += $d
  Write-Output ("LINE {0} {1}s" -f ($i + 1), $d)
}

$listPath = Join-Path $Dir 'vlist.txt'
if (Test-Path $listPath) { Remove-Item $listPath -Force }
for ($i = 0; $i -lt $lines.Count; $i++) {
  $n = $i + 1
  $img = Join-Path $Dir ("s{0:d2}.png" -f $n)
  $wav = Join-Path $Dir ("v{0:d2}.wav" -f $n)
  $out = Join-Path $Dir ("c{0:d2}.mp4" -f $n)
  $len = [math]::Round($durs[$i] + 0.45, 3)
  $frames = [int]([math]::Round($len * 30))
  if (Test-Path $out) { Remove-Item $out -Force }
  $vf = "scale=1188:2112,zoompan=z='min(zoom+0.0008,1.10)':d=$frames" +
        ":x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920:fps=30,format=yuv420p"
  & $FF -y -loglevel error -loop 1 -i $img -i $wav -t $len -r 30 `
      -vf $vf -af "adelay=200|200,apad" -t $len `
      -c:v libx264 -preset veryfast -crf 20 -c:a aac -b:a 192k -ar 44100 -ac 2 $out
  Add-Content -Path $listPath -Value ("file '" + ("c{0:d2}.mp4" -f $n) + "'") -Encoding ASCII
}

$voiced = Join-Path $Dir 'voiced.mp4'
if (Test-Path $voiced) { Remove-Item $voiced -Force }
Push-Location $Dir
& $FF -y -loglevel error -f concat -safe 0 -i vlist.txt -c copy voiced.mp4
Pop-Location

$total = 0.0; foreach ($d in $durs) { $total += ($d + 0.45) }
$total = [math]::Round($total, 2)
$fadeStart = [math]::Max(0, $total - 2.0)
$final = Join-Path $Dir 'final.mp4'
if (Test-Path $final) { Remove-Item $final -Force }

$fc = "[1:a]atrim=0:$total,afade=t=in:st=0:d=1.2,afade=t=out:st=$fadeStart" +
      ":d=2,volume=0.11[bg];" +
      "[0:a]aresample=44100,volume=1.6,alimiter=limit=0.95,asplit=2[vo1][vo2];" +
      "[bg][vo1]sidechaincompress=threshold=0.05:ratio=7:attack=8:release=350[bgd];" +
      "[vo2][bgd]amix=inputs=2:duration=first:dropout_transition=0," +
      "loudnorm=I=-14:TP=-1.5:LRA=11[a]"

& $FF -y -loglevel error -i $voiced -i $BGM -filter_complex $fc `
    -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k $final

Write-Output ("TOTAL " + $total + "s")
Write-Output ("DONE " + $final)
