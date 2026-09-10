# -*- coding: utf-8 -*-
"""후보 목소리 4종을 같은 문장으로 뽑아 비교용 mp3 를 만든다."""
import asyncio, edge_tts, os
OUT = r"C:\CallRadar\_video\voicetest"
TXT = ("인천공항 일터미널, 서울 방향 택시 스물여덟 대입니다. "
       "평소 백 대 넘게 서 있는 자린데 오늘은 스물여덟 대예요. "
       "지금 출발해도 앞에 서른 대가 안 됩니다. 오늘 공항은 갈 만합니다.")
V = ["ko-KR-InJoonNeural", "ko-KR-SunHiNeural",
     "ko-KR-HyunsuMultilingualNeural", "ko-KR-GookMinNeural",
     "ko-KR-YuJinNeural", "ko-KR-SeoHyeonNeural"]
async def main():
    os.makedirs(OUT, exist_ok=True)
    for v in V:
        try:
            await edge_tts.Communicate(TXT, v, rate="+8%").save(
                os.path.join(OUT, v.split("-")[-1].replace("Neural", "") + ".mp3"))
            print("ok", v)
        except Exception as e:
            print("ERR", v, type(e).__name__, str(e)[:100])
asyncio.run(main())
