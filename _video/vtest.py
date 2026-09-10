import asyncio, edge_tts
TXT="인천공항 일터미널, 서울 방향 택시 스물여덟 대입니다. 평소 백 대 넘게 서 있는 자린데, 오늘은 스물여덟 대예요."
VOICES=["ko-KR-SunHiNeural","ko-KR-InJoonNeural","ko-KR-HyunsuMultilingualNeural"]
async def main():
    for v in VOICES:
        try:
            c=edge_tts.Communicate(TXT, v, rate="+8%")
            await c.save(f"voice_{v.split('-')[-1]}.mp3")
            print("ok", v)
        except Exception as e:
            print("ERR", v, type(e).__name__, str(e)[:120])
asyncio.run(main())
