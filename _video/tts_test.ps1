$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
"=== 설치된 음성 목록 ==="
$s.GetInstalledVoices() | ForEach-Object {
  $i = $_.VoiceInfo
  "{0} | {1} | {2}" -f $i.Name, $i.Culture, $i.Gender
}
$ko = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'ko*' }
if ($ko) {
  $s.SelectVoice($ko[0].VoiceInfo.Name)
  "선택된 한국어 음성: " + $ko[0].VoiceInfo.Name
  $s.SetOutputToWaveFile("C:\CallRadar\_video\tts_sample.wav")
  $s.Speak("콜레이더입니다. 오늘은 자동 기록 기능을 소개합니다. 카카오티, 우버, 티머니고 화면을 읽어 운행이 알아서 기록됩니다.")
  $s.SetOutputToDefaultAudioDevice()
  "WAV 생성 완료"
} else {
  "한국어 음성 없음 - edge-tts 등 대안 필요"
}
