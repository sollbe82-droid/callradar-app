param([string]$Dir = "C:\CallRadar\_video\short_20260908")

# ============================================================
#  콜레이더 쇼츠 제작 — 음성(SAPI) + 슬라이드 + BGM
#  사용: powershell -ExecutionPolicy Bypass -File make_short.ps1 -Dir <폴더>
#  폴더 안에 s01.png.. 와 lines.json 이 있어야 한다.
# ============================================================
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$FF  = "C:\CallRadar\ffmpeg.exe"
$BGM = "C:\CallRadar\_video\bgm\bgm_brief.wav"

Add-Type -AssemblyName System.Speech
$sp = New-Object System.Speech.Synthesis.SpeechSynthesizer
$ko = $sp.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'ko*' }
if (-not $ko) { Write-Output "NO_KOREAN_VOICE"; exit 1 }
$sp.SelectVoice($ko[0].VoiceInfo.Name)
Write-Output ("VOICE " + $ko[0].VoiceInfo.Name)

$json  = Get-Content (Join-Path $Dir 'lines.json') -Raw -Encoding UTF8
$lines = $json | ConvertFrom-Json

# WAV 길이를 헤더에서 직접 읽는다 (ffprobe 없이)
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
        $p = $fs.Position
        $br.ReadBytes(8) | Out-Null
        $byteRate = $br.ReadInt32()
        $fs.Position = $p + $sz
      } elseif ($id -eq 'data') {
        $dataSize = $sz; break
      } else {
        $fs.Position = $fs.Position + $sz + ($sz % 2)
      }
    }
    if ($byteRate -le 0) { return 4.0 }
    return [math]::Round($dataSize / $byteRate, 3)
  } finally { $fs.Close() }
}

# ---- 1) 문장별 음성 생성 (속도를 조금씩 흔들어 기계 느낌을 줄인다) ----
$rates = @(0, -1, 0, 1, 0, -1, 1, 0)
$durs  = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
  $wav = Join-Path $Dir ("v{0:d2}.wav" -f ($i + 1))
  if (Test-Path $wav) { Remove-Item $wav -Force }
  $sp.Rate = $rates[$i % $rates.Count]
  $sp.SetOutputToWaveFile($wav)
  $sp.Speak($lines[$i].say)
  $sp.SetOutputToDefaultAudioDevice()
  $d = Get-WavSeconds $wav
  $durs += $d
  Write-Output ("LINE {0} {1}s  {2}" -f ($i + 1), $d, $lines[$i].say)
}

# ---- 2) 문장별 클립 (이미지 + 그 문장 음성 + 앞뒤 여백) ----
$listPath = Join-Path $Dir 'vlist.txt'
if (Test-Path $listPath) { Remove-Item $listPath -Force }
for ($i = 0; $i -lt $lines.Count; $i++) {
  $n   = $i + 1
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

# ---- 3) 이어붙이기 ----
$voiced = Join-Path $Dir 'voiced.mp4'
if (Test-Path $voiced) { Remove-Item $voiced -Force }
Push-Location $Dir
& $FF -y -loglevel error -f concat -safe 0 -i vlist.txt -c copy voiced.mp4
Pop-Location

# ---- 4) BGM 을 말소리 아래로 깔고 최종본 ----
$total = 0.0; foreach ($d in $durs) { $total += ($d + 0.45) }
$total = [math]::Round($total, 2)
$fadeStart = [math]::Max(0, $total - 2.0)
$final = Join-Path $Dir 'final.mp4'
if (Test-Path $final) { Remove-Item $final -Force }

# 말소리 0dB / BGM -19dB. 말이 나올 때 BGM 을 더 눌러 준다(sidechaincompress).
$fc = "[1:a]atrim=0:$total,afade=t=in:st=0:d=1.2,afade=t=out:st=$fadeStart" +
      ":d=2,volume=0.11[bg];" +
      "[0:a]aresample=44100,volume=1.6,alimiter=limit=0.95,asplit=2[vo1][vo2];" +
      "[bg][vo1]sidechaincompress=threshold=0.05:ratio=7:attack=8:release=350[bgd];" +
      "[vo2][bgd]amix=inputs=2:duration=first:dropout_transition=0," +
      "loudnorm=I=-14:TP=-1.5:LRA=11[a]"
# ★ loudnorm 필수: 유튜브는 -14 LUFS 기준으로 큰 소리만 깎고 작은 소리는 안 올린다.
#    이걸 빼면 다른 영상보다 확연히 작게 들린다(첫 시도 -22.7 LUFS).

& $FF -y -loglevel error -i $voiced -i $BGM -filter_complex $fc `
    -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k $final

Write-Output ("TOTAL " + $total + "s")
Write-Output ("DONE " + $final)
