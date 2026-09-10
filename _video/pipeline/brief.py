# -*- coding: utf-8 -*-
"""
그날 데이터를 모아 대본(lines.json)과 업로드 정보(meta.json)를 만든다.

핵심 규칙
  · 요일로 포맷을 정하지 않는다. 그날 데이터가 무엇을 주느냐로 정한다.
    (그래야 매일 내용이 저절로 달라지고, 자동으로 안 보인다)
  · 표본이 모자라면 비교 표현을 쓰지 않는다.
    "평소보다 적습니다" 는 평소를 알 때만 할 수 있는 말이다.
"""
import os, json, datetime, statistics, sys
import requests

BASE  = os.path.dirname(os.path.abspath(__file__))
ROOT  = os.path.dirname(BASE)                     # C:\CallRadar\_video
SRV   = "https://callradar-server.onrender.com"
HIST  = os.path.join(ROOT, "airport_history.json")
KST   = datetime.timezone(datetime.timedelta(hours=9))

WD = ["월", "화", "수", "목", "금", "토", "일"]


def now_kst():
    return datetime.datetime.now(KST)


def get(path, timeout=60):
    r = requests.get(SRV + path, timeout=timeout)
    r.raise_for_status()
    return r.json()


# ---------------------------------------------------------------- 데이터
def fetch():
    d = {"ok": {}, "err": []}
    try:
        a = get("/api/airport/cached")
        d["airport"] = a
        d["ok"]["airport"] = bool(a.get("taxiOk", True))
    except Exception as e:
        d["airport"] = None
        d["err"].append("airport:%s" % type(e).__name__)
    try:
        ev = get("/api/events")
        d["events"] = ev if isinstance(ev, list) else (ev.get("events") or ev.get("rows") or [])
    except Exception as e:
        d["events"] = []
        d["err"].append("events:%s" % type(e).__name__)
    return d


def taxi_total(term):
    """서울·인천·경기 방향 합계. 모범·대형은 성격이 달라 뺀다."""
    t = (term or {}).get("taxi") or {}
    return sum(int(t.get(k) or 0) for k in ("seoul", "incheon", "gyeonggi"))


def push_history(t1, t2):
    """오늘 값을 기록하고, 표본이 7일 이상이면 중앙값을 돌려준다.
    평균이 아니라 중앙값을 쓴다 — 하루 이상값에 휘둘리면 조언이 거짓이 된다."""
    hist = []
    if os.path.exists(HIST):
        try:
            hist = json.load(open(HIST, encoding="utf-8"))
        except Exception:
            hist = []
    n = now_kst()
    hist = [h for h in hist if h.get("d") != n.strftime("%Y-%m-%d %H")]
    hist.append({"d": n.strftime("%Y-%m-%d %H"), "h": n.hour, "t1": t1, "t2": t2})
    hist = hist[-2000:]
    json.dump(hist, open(HIST, "w", encoding="utf-8"), ensure_ascii=False)

    # 같은 시간대(±2시간)만 비교한다. 새벽과 저녁을 같이 놓으면 의미가 없다.
    same = [h["t1"] for h in hist[:-1] if abs(h.get("h", 12) - n.hour) <= 2 and h.get("t1")]
    if len(same) >= 7:
        return int(statistics.median(same)), len(same)
    return None, len(same)


def kst_hhmm(iso):
    try:
        dt = datetime.datetime.fromisoformat(str(iso).replace("Z", "+00:00"))
        return dt.astimezone(KST).strftime("%H:%M")
    except Exception:
        return None


def today_events(evs):
    """오늘(KST) 수도권에서 열리고 끝나는 시각을 아는 행사만."""
    today = now_kst().date()
    out = []
    for e in evs:
        s = e.get("start_at")
        if not s:
            continue
        try:
            sd = datetime.datetime.fromisoformat(str(s).replace("Z", "+00:00")).astimezone(KST)
        except Exception:
            continue
        if sd.date() != today:
            continue
        area = (e.get("area") or "")
        ven = (e.get("venue") or "") + (e.get("title") or "")
        metro = area in ("서울", "경기", "인천") or any(
            k in ven for k in ("잠실", "고척", "킨텍스", "올림픽", "DDP", "상암", "수원", "인천"))
        if not metro:
            continue
        end = e.get("end_at")
        # start_at == end_at 이면 파장 시각을 모르는 것이다. 모르면 안 쓴다.
        has_end = bool(end) and str(end) != str(s)
        out.append({
            "title": e.get("title"), "venue": e.get("venue"), "area": area,
            "cat": e.get("category"), "src": e.get("source"),
            "start": kst_hhmm(s), "end": kst_hhmm(end) if has_end else None,
        })
    return out


# ---------------------------------------------------------------- 대본
def build(d):
    n = now_kst()
    date_ko = "%d월 %d일 %s요일" % (n.month, n.day, WD[n.weekday()])

    t1 = t2 = None
    upd = None
    if d.get("airport") and d["ok"].get("airport"):
        t1 = taxi_total(d["airport"].get("t1"))
        t2 = taxi_total(d["airport"].get("t2"))
        upd = ((d["airport"].get("t1") or {}).get("taxi") or {}).get("updateTime")
    med, nsample = push_history(t1, t2)

    evs = today_events(d.get("events") or [])
    games = [e for e in evs if e["cat"] == "스포츠" and e["end"]]
    bigs  = [e for e in evs if e["cat"] == "대형행사" and e["end"]]

    lines, fmt = [], None

    # --- 오늘의 머리기사 고르기 (우선순위: 대형행사 > 공항 극단 > 야구 > 기본)
    airport_extreme = None
    if t1 is not None:
        if med:
            if t1 <= med * 0.6:
                airport_extreme = "low"
            elif t1 >= med * 1.5:
                airport_extreme = "high"
        else:
            if t1 <= 40:
                airport_extreme = "low"
            elif t1 >= 130:
                airport_extreme = "high"

    def L(kicker, big, sub, say, size=130, accent=None):
        o = {"kicker": kicker, "big": big, "sub": sub, "say": say, "size": size}
        if accent:
            o["accent"] = accent
        lines.append(o)

    if bigs:
        fmt = "big_event"
        b = bigs[0]
        L(date_ko + " · 오늘 밤", "%s\n%s 종료" % (b["venue"] or b["title"], b["end"]),
          b["title"] or "", "오늘 밤 %s, %s에 끝납니다." % (b["venue"] or b["title"], b["end"]),
          size=104)
    elif airport_extreme == "low":
        fmt = "airport_go"
        L(date_ko + " · 인천공항 1터미널", "서울 방향\n택시 %d대" % t1,
          ("평소 이 시간 %d대입니다." % med) if med else "지금 대기 줄이 짧습니다.",
          "인천공항 일터미널, 서울 방향 택시 %s대입니다." % num_ko(t1), size=126)
        L("지금 출발하면", "도착해도\n줄이 짧습니다", "왕복 두 시간이 아깝지 않은 날입니다.",
          "지금 출발해서 한 시간 뒤 도착해도 앞에 서른 대가 안 됩니다.", size=112)
        L("결론", "오늘 공항은\n갈 만합니다", "", "오늘 공항은 갈 만합니다.",
          size=118, accent=[45, 212, 191])
    elif airport_extreme == "high":
        fmt = "airport_stop"
        L(date_ko + " · 인천공항 1터미널", "서울 방향\n택시 %d대" % t1,
          ("평소 이 시간 %d대입니다." % med) if med else "지금 대기 줄이 깁니다.",
          "인천공항 일터미널, 서울 방향 택시 %s대입니다." % num_ko(t1), size=126)
        L("지금 출발하면", "도착 뒤\n한 시간 넘게 대기", "가는 데 50분, 기름값 만 이천 원.",
          "지금 출발하면 도착해서 한 시간 넘게 기다립니다. 가는 데만 오십 분입니다.", size=100)
        L("결론", "오늘 공항은\n아닙니다", "", "오늘 공항은 아닙니다.",
          size=126, accent=[239, 68, 68])
    elif games:
        fmt = "baseball"
        g = games[0]
        L(date_ko + " · 오늘 야구", "%s\n%s 종료" % (g["venue"], g["end"]),
          g["title"].replace("⚾ ", ""), "오늘 %s 경기, 예상 종료가 %s입니다."
          % (g["venue"], time_ko(g["end"])), size=104)
    else:
        fmt = "airport_plain"
        if t1 is not None:
            L(date_ko + " · 인천공항 1터미널", "서울 방향\n택시 %d대" % t1,
              ("평소 이 시간 %d대입니다." % med) if med else "",
              "인천공항 일터미널, 서울 방향 택시 %s대입니다." % num_ko(t1), size=126)

    # --- 야구 (머리기사가 야구가 아니어도 붙인다)
    if games and fmt != "baseball":
        g = games[0]
        if len(games) >= 2:
            L("오늘 야구", "%s\n%s" % (games[0]["venue"], games[1]["venue"]),
              "둘 다 예상 종료 %s" % games[0]["end"],
              "야구는 두 경기입니다. %s, %s. 둘 다 예상 종료가 %s입니다."
              % (venue_ko(games[0]["venue"]), venue_ko(games[1]["venue"]), time_ko(games[0]["end"])),
              size=100)
        else:
            L("오늘 야구", "%s\n%s 종료" % (g["venue"], g["end"]), g["title"].replace("⚾ ", ""),
              "야구는 %s 한 경기입니다. 예상 종료 %s."
              % (venue_ko(g["venue"]), time_ko(g["end"])), size=104)
    if games:
        g = games[0]
        before = minus30(g["end"])
        L("파장을 잡으려면", "%s까지\n근처 도착" % before, "파장 30분 전에 이미 줄이 섭니다.",
          "파장에 맞추려면 %s에는 근처에 계셔야 합니다. 파장 삼십 분 전부터 이미 줄이 섭니다."
          % time_ko(before), size=126)
    if len(games) >= 2:
        g2 = games[1]
        L("경기 두 개가 겹칩니다", "가까운 쪽\n하나만", "%s는 파장 때 택시가 몰립니다." % games[0]["venue"],
          "경기가 두 개니까 가까운 쪽 하나만 잡으십시오. %s는 파장 때 택시가 몰립니다."
          % venue_ko(games[0]["venue"]), size=118)

    # --- 공항과 행사가 둘 다 있으면 선택 기준을 준다 (숫자만 주면 기사가 계산해야 한다)
    if t1 is not None and (games or bigs):
        L("오늘 밤 선택", "왕복 2시간이면 공항\n짧게 여러 번이면 행사",
          "공항은 한 탕이 크고, 행사는 회전이 빠릅니다.",
          "공항이냐 행사냐. 왕복 두 시간을 쓸 거면 공항, 짧게 여러 번 돌 거면 행사입니다.",
          size=74)

    # --- 공항이 머리기사가 아니었으면 숫자만 한 줄
    if fmt not in ("airport_go", "airport_stop", "airport_plain") and t1 is not None:
        L("같은 시각 인천공항", "T1 %d대\nT2 %d대" % (t1, t2),
          "서울·인천·경기 방향 합계입니다.",
          "같은 시각 인천공항은 일터미널 %s대, 이터미널 %s대입니다."
          % (num_ko(t1), num_ko(t2)), size=112)

    # --- 마무리
    L("", "매일 저녁\n정리해 드립니다", "콜레이더 · 기사분들 실제 운행 기록 기준",
      "콜레이더 데이터 기준으로 매일 저녁 정리해 드립니다.",
      size=118, accent=[45, 212, 191])

    return lines, fmt, {"t1": t1, "t2": t2, "median": med, "samples": nsample,
                        "updateTime": upd, "games": games, "bigs": bigs,
                        "date_ko": date_ko, "err": d.get("err", [])}


# ---------------------------------------------------------------- 표기 도우미
_UNITS = ["", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"]
_TENS = ["", "십", "이십", "삼십", "사십", "오십", "육십", "칠십", "팔십", "구십"]


def num_ko(n):
    """TTS 가 숫자를 어색하게 읽는 경우가 있어 한글로 풀어 준다."""
    n = int(n or 0)
    if n >= 1000:
        return str(n)
    out = ""
    if n >= 100:
        out += _UNITS[n // 100] + "백"
        n %= 100
    if n >= 10:
        out += _TENS[n // 10]
        n %= 10
    if n:
        out += _UNITS[n]
    return out or "영"


_H = ["열두", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "열한"]


def time_ko(hhmm):
    if not hhmm:
        return ""
    h, m = int(hhmm[:2]), int(hhmm[3:5])
    return "%s 시%s" % (_H[h % 12], (" %s 분" % num_ko(m)) if m else "")


def minus30(hhmm):
    h, m = int(hhmm[:2]), int(hhmm[3:5])
    t = (h * 60 + m - 30) % (24 * 60)
    return "%02d:%02d" % (t // 60, t % 60)


def venue_ko(v):
    return (v or "").replace("야구장", "").replace("파크", " 파크")


# ---------------------------------------------------------------- 메타
def meta(lines, fmt, info):
    head = lines[0]
    title_map = {
        "airport_go":   "인천공항 택시 %s대, 오늘은 가도 됩니다" % info["t1"],
        "airport_stop": "인천공항 택시 %s대, 오늘은 가지 마세요" % info["t1"],
        "airport_plain": "인천공항 택시 %s대 · %s 저녁 브리핑" % (info["t1"], info["date_ko"]),
        "baseball":     "%s 파장 %s, 몇 시에 출발해야 하나" % (
            info["games"][0]["venue"] if info["games"] else "",
            info["games"][0]["end"] if info["games"] else ""),
        "big_event":    "%s %s 종료 · 오늘 밤 어디로" % (
            info["bigs"][0]["venue"] if info["bigs"] else "",
            info["bigs"][0]["end"] if info["bigs"] else ""),
    }
    title = (title_map.get(fmt) or ("%s 저녁 브리핑" % info["date_ko"])) + " #Shorts"

    data_lines = []
    if info["t1"] is not None:
        data_lines.append("· 인천공항 T1 서울·인천·경기 방향 택시  %d대%s"
                          % (info["t1"], (" (%s 기준)" % info["updateTime"]) if info["updateTime"] else ""))
        data_lines.append("· T2  %d대" % info["t2"])
    if info["median"]:
        data_lines.append("· 같은 시간대 평소값(중앙값, 표본 %d일)  %d대" % (info["samples"], info["median"]))
    for g in info["games"]:
        data_lines.append("· %s  %s 시작 / 예상 종료 %s"
                          % (g["venue"], g["start"], g["end"]))
    for b in info["bigs"]:
        data_lines.append("· %s  %s / 종료 %s" % (b["title"], b["venue"], b["end"]))

    desc = (
        "%s 저녁 브리핑입니다.\n%s\n\n"
        "────────────────────\n📍 오늘 데이터\n%s\n\n"
        "📊 출처\n공공데이터포털 인천국제공항공사 택시출차 정보\n"
        "한국야구위원회 경기일정 · 한국관광공사 · 공연예술통합전산망\n"
        "콜레이더 이용 기사분들의 실제 운행 기록\n\n"
        "🚕 콜레이더 — 택시 운행기록·수입관리 앱 (무료)\n"
        "구글플레이  https://play.google.com/store/apps/details?id=com.callradar.app\n\n"
        "궁금한 거 댓글 주세요. 데이터로 확인해서 다음 영상에서 답해 드립니다.\n\n"
        "#택시기사 #택시 #콜레이더"
    ) % (info["date_ko"], head["sub"] or head["big"].replace("\n", " "), "\n".join(data_lines))

    tags = ["콜레이더", "택시", "택시기사", "법인택시", "개인택시", "택시운행기록",
            "택시가계부", "택시수입", "택시매출", "택시앱", "택시기사수입",
            "카카오T", "카카오택시", "우버택시", "티머니택시", "인천공항택시",
            "공항택시", "택시콜", "사납금", "택시기사브이로그", "택시노하우",
            "택시꿀팁", "야간택시", "심야할증", "택시기사일상", "운행기록",
            "매출관리", "택시운전", "택시정보"]
    extra = {"airport_go": ["인천공항", "공항대기", "1터미널"],
             "airport_stop": ["인천공항", "공항대기", "1터미널"],
             "airport_plain": ["인천공항", "공항대기"],
             "baseball": ["잠실야구장", "야구파장", "KBO", "프로야구"],
             "big_event": ["콘서트", "행사", "파장"]}.get(fmt, [])
    for g in info["games"]:
        v = (g["venue"] or "").replace(" ", "")
        if v and v not in tags:
            extra.append(v)
    for t in extra:                       # 중복 태그는 낭비다(500자 제한)
        if t not in tags:
            tags.append(t)

    return {"title": title[:100], "description": desc, "tags": tags,
            "categoryId": "22", "privacyStatus": "private"}


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "short_" + now_kst().strftime("%Y%m%d"))
    os.makedirs(outdir, exist_ok=True)
    d = fetch()
    lines, fmt, info = build(d)
    if len(lines) < 3:
        print("TOO_FEW_LINES %d — 데이터를 못 받았습니다: %s" % (len(lines), info["err"]))
        sys.exit(2)
    json.dump(lines, open(os.path.join(outdir, "lines.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    json.dump(meta(lines, fmt, info),
              open(os.path.join(outdir, "meta.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print("FORMAT", fmt)
    print("LINES", len(lines))
    print("T1", info["t1"], "T2", info["t2"], "MEDIAN", info["median"],
          "SAMPLES", info["samples"])
    print("DIR", outdir)


if __name__ == "__main__":
    main()
