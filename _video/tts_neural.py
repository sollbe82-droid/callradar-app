# -*- coding: utf-8 -*-
"""
콜레이더 쇼츠 음성 — Microsoft Neural TTS (edge-tts)
  python tts_neural.py <폴더> [voice]
lines.json 의 say[] 를 읽어 v01.wav .. 로 만든다. 기존 SAPI(Heami) 대신 쓴다.

설치: pip install edge-tts
"""
import asyncio, json, os, sys, subprocess

DIR   = sys.argv[1] if len(sys.argv) > 1 else r"C:\CallRadar\_video\short_20260908"
VOICE = sys.argv[2] if len(sys.argv) > 2 else "ko-KR-InJoonNeural"
FF    = r"C:\CallRadar\ffmpeg.exe"

import edge_tts

# 문장마다 속도·높이를 조금씩 흔든다. 완전히 균일하면 기계로 들린다.
RATES = ["+6%", "+10%", "+4%", "+8%", "+6%", "+12%", "+5%", "+8%"]
PITCH = ["+0Hz", "+2Hz", "-2Hz", "+0Hz", "+3Hz", "-1Hz", "+0Hz", "+2Hz"]


async def main():
    lines = json.load(open(os.path.join(DIR, "lines.json"), encoding="utf-8"))
    for i, L in enumerate(lines):
        mp3 = os.path.join(DIR, "v%02d.mp3" % (i + 1))
        wav = os.path.join(DIR, "v%02d.wav" % (i + 1))
        c = edge_tts.Communicate(L["say"], VOICE,
                                 rate=RATES[i % len(RATES)],
                                 pitch=PITCH[i % len(PITCH)])
        await c.save(mp3)
        # ffmpeg 파이프라인이 wav 를 기대하므로 변환
        subprocess.run([FF, "-y", "-loglevel", "error", "-i", mp3,
                        "-ar", "44100", "-ac", "1", wav], check=True)
        os.remove(mp3)
        print("LINE %d ok  %s" % (i + 1, L["say"][:40]))
    print("VOICE " + VOICE)


asyncio.run(main())
