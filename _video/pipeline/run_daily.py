# -*- coding: utf-8 -*-
"""
하루 한 번 도는 전체 파이프라인.
  데이터 수집 -> 대본 -> 슬라이드 -> 음성/영상 -> 업로드(비공개) -> 보고서

작업 스케줄러가 이것 하나만 부르면 된다.
  python run_daily.py            평소
  python run_daily.py --dry      업로드 없이 영상까지만
"""
import os, sys, json, random, time, datetime, subprocess, traceback

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                       # C:\CallRadar\_video
PY   = sys.executable
KST  = datetime.timezone(datetime.timedelta(hours=9))
REPORT = os.path.join(ROOT, "daily_report.txt")
STATE  = os.path.join(ROOT, "daily_state.json")

DRY = "--dry" in sys.argv


def now():
    return datetime.datetime.now(KST)


def log(msg):
    line = "[%s] %s" % (now().strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    with open(REPORT, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def sh(args, name):
    log("RUN " + name)
    p = subprocess.run(args, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    for ln in (p.stdout or "").splitlines():
        log("  " + ln)
    if p.returncode != 0:
        log("  ERR " + (p.stderr or "")[-800:])
        raise RuntimeError("%s failed rc=%d" % (name, p.returncode))
    return p.stdout or ""


def rest_day():
    """일요일은 쉰다. 사람은 매일 올리지 않는다 — 매일 올리는 게 자동 티의 1순위다."""
    return now().weekday() == 6


def jitter_sleep():
    """정각 업로드는 자동 티가 난다. 0~40분 사이 무작위로 늦춘다."""
    if DRY:
        return
    s = random.randint(0, 40 * 60)
    log("JITTER %d분 %d초 대기" % (s // 60, s % 60))
    time.sleep(s)


def token_age_warning():
    """게시 상태가 '테스트'라 인증이 7일마다 만료된다.
    끊기고 나서 알면 그날 영상이 날아가므로 5일째부터 미리 알린다."""
    tok = os.path.join(ROOT, "token.json")
    if not os.path.exists(tok):
        return "인증 파일이 없습니다. yt_auth.py 를 돌려 주세요."
    age = (time.time() - os.path.getmtime(tok)) / 86400.0
    if age >= 6.0:
        return "★ 유튜브 인증이 곧 만료됩니다(%.1f일 경과). 오늘 재인증하세요." % age
    if age >= 5.0:
        return "유튜브 인증 %.1f일 경과 — 이번 주 안에 재인증이 필요합니다." % age
    return None


def main():
    open(REPORT, "w", encoding="utf-8").write(
        "콜레이더 유튜브 · %s\n" % now().strftime("%Y-%m-%d %H:%M"))

    state = {"date": now().strftime("%Y-%m-%d"), "ok": False}

    if rest_day():
        log("SKIP 일요일은 쉽니다")
        state["ok"] = True
        state["skipped"] = "sunday"
        json.dump(state, open(STATE, "w", encoding="utf-8"), ensure_ascii=False)
        return

    warn = token_age_warning()
    if warn:
        log("TOKEN " + warn)
        state["token_warn"] = warn

    outdir = os.path.join(ROOT, "short_" + now().strftime("%Y%m%d"))

    try:
        sh([PY, os.path.join(HERE, "brief.py"), outdir], "brief")
        sh([PY, os.path.join(HERE, "slides.py"), outdir], "slides")
        sh([PY, os.path.join(HERE, "build_video.py"), outdir], "video")

        if DRY:
            log("DRY 업로드 생략")
            state["ok"] = True
            state["dir"] = outdir
        else:
            jitter_sleep()
            out = sh([PY, os.path.join(ROOT, "yt_upload.py"), outdir], "upload")
            for ln in out.splitlines():
                if ln.startswith("URL "):
                    state["url"] = ln[4:].strip()
                if ln.startswith("VIDEO_ID "):
                    state["video_id"] = ln[9:].strip()
            state["ok"] = True
            state["dir"] = outdir
            log("업로드 완료(비공개). 대표 확인 후 공개로 바꾸시면 됩니다.")
    except Exception as e:
        log("FAIL " + repr(e))
        log(traceback.format_exc()[-1200:])
        state["ok"] = False
        state["error"] = repr(e)

    json.dump(state, open(STATE, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    log("STATE " + json.dumps(state, ensure_ascii=False))


if __name__ == "__main__":
    main()
