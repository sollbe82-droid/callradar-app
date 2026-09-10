# -*- coding: utf-8 -*-
"""
음성(edge-tts) + 슬라이드 + BGM -> final.mp4
  python build_video.py <폴더> [voice]
"""
import os, sys, json, wave, asyncio, subprocess, random

FF   = r"C:\CallRadar\ffmpeg.exe"
BGM  = r"C:\CallRadar\_video\bgm\bgm_brief.wav"
DIR  = sys.argv[1]
VOICE = sys.argv[2] if len(sys.argv) > 2 else "ko-KR-InJoonNeural"

import edge_tts

RATES = ["+6%", "+10%", "+4%", "+8%", "+6%", "+12%", "+5%", "+8%", "+7%"]
PITCH = ["+0Hz", "+2Hz", "-2Hz", "+0Hz", "+3Hz", "-1Hz", "+0Hz", "+2Hz", "+1Hz"]


def run(args):
    p = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        raise RuntimeError("ffmpeg failed: " + (p.stderr or "")[-600:])
    return p


async def tts(lines):
    for i, L in enumerate(lines):
        mp3 = os.path.join(DIR, "v%02d.mp3" % (i + 1))
        wav = os.path.join(DIR, "v%02d.wav" % (i + 1))
        c = edge_tts.Communicate(L["say"], VOICE,
                                 rate=RATES[i % len(RATES)], pitch=PITCH[i % len(PITCH)])
        await c.save(mp3)
        run([FF, "-y", "-loglevel", "error", "-i", mp3, "-ar", "44100", "-ac", "1", wav])
        os.remove(mp3)


def wav_seconds(path):
    with wave.open(path, "rb") as w:
        return round(w.getnframes() / float(w.getframerate()), 3)


def main():
    lines = json.load(open(os.path.join(DIR, "lines.json"), encoding="utf-8"))
    asyncio.run(tts(lines))

    durs, listp = [], os.path.join(DIR, "vlist.txt")
    with open(listp, "w", encoding="ascii") as lf:
        for i in range(len(lines)):
            n = i + 1
            wav = os.path.join(DIR, "v%02d.wav" % n)
            img = os.path.join(DIR, "s%02d.png" % n)
            out = os.path.join(DIR, "c%02d.mp4" % n)
            dur = wav_seconds(wav) + 0.45
            durs.append(dur)
            frames = int(round(dur * 30))
            vf = ("scale=1188:2112,zoompan=z='min(zoom+0.0008,1.10)':d=%d"
                  ":x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920:fps=30,"
                  "format=yuv420p") % frames
            run([FF, "-y", "-loglevel", "error", "-loop", "1", "-i", img, "-i", wav,
                 "-t", "%.3f" % dur, "-r", "30", "-vf", vf,
                 "-af", "adelay=200|200,apad", "-t", "%.3f" % dur,
                 "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                 "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "2", out])
            lf.write("file 'c%02d.mp4'\n" % n)

    voiced = os.path.join(DIR, "voiced.mp4")
    run([FF, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0",
         "-i", listp, "-c", "copy", voiced])

    total = round(sum(durs), 2)
    fade = max(0, total - 2.0)
    fc = ("[1:a]atrim=0:%s,afade=t=in:st=0:d=1.2,afade=t=out:st=%s:d=2,volume=0.11[bg];"
          "[0:a]aresample=44100,volume=1.6,alimiter=limit=0.95,asplit=2[vo1][vo2];"
          "[bg][vo1]sidechaincompress=threshold=0.05:ratio=7:attack=8:release=350[bgd];"
          "[vo2][bgd]amix=inputs=2:duration=first:dropout_transition=0,"
          # ★ 유튜브는 -14 LUFS 기준으로 큰 소리만 깎고 작은 소리는 안 올린다.
          #   이걸 빼면 다른 영상보다 확연히 작게 들린다.
          "loudnorm=I=-14:TP=-1.5:LRA=11[a]") % (total, fade)

    final = os.path.join(DIR, "final.mp4")
    run([FF, "-y", "-loglevel", "error", "-i", voiced, "-i", BGM,
         "-filter_complex", fc, "-map", "0:v", "-map", "[a]",
         "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", final])

    # 정리 — 조각 파일은 남길 이유가 없다
    for i in range(len(lines)):
        for pat in ("c%02d.mp4", "v%02d.wav"):
            p = os.path.join(DIR, pat % (i + 1))
            if os.path.exists(p):
                os.remove(p)
    for p in (voiced, listp):
        if os.path.exists(p):
            os.remove(p)

    print("TOTAL %.2f" % total)
    print("DONE", final)
    if total > 175:
        print("WARN 쇼츠 상한(3분)에 가깝습니다")


if __name__ == "__main__":
    main()
