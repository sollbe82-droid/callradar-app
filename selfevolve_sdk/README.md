# Self-Evolve Engine — 도메인 독립 자기진화 니즈발굴 엔진

콜레이더에서 검증된 "자기진화 텔레메트리 루프"를 **어떤 앱/프로그램에도 꽂을 수 있는 재사용 SDK**로 뽑아낸 것.
익명 사용·행동·결과 이벤트를 모아 → 집계 → **"실패·이탈·반복시도 = 유저가 말하지 않은 니즈"**를 찾아 내부 리포트로 돌려준다.
그 리포트로 개발자는 **유저가 요청하지 않은 기능까지** 데이터로 발굴해 만든다.

> ⚠️ 개인정보 미수집. 무작위 anon_id + domain + event + context + 성공여부 + 수치 meta만. 옵트아웃 기본 제공.

---

## 루프 (5단계)
1. **수집** — 클라이언트가 익명 이벤트 전송 (`event`, `context`, `ok`).
2. **집계** — 서버가 도메인별로 모음.
3. **니즈 발굴** — `ok=false`(시도했으나 실패/이탈)가 많은 `(event, context)` = **미발화 니즈**.
4. **리포트** — 활성유저·최다이벤트·핫컨텍스트·숨은니즈를 내부 보고서로.
5. **진화** — 그 리포트로 개발 우선순위 자동화. (도메인 애널라이저·LLM은 플러그인/추후.)

## 구성 파일
- `server/selfevolve.js` — Node/Express 재사용 엔진. `domain` 컬럼으로 **여러 종목이 1DB 공유**. `POST /events`, `GET /report`.
- `clients/SelfEvolveClient.kt` — **앱용**(Android/Kotlin). 큐·배치전송·옵트아웃.
- `clients/selfevolve-web.js` — **컴퓨터/웹용**(브라우저/Electron). sendBeacon 배치.

## 통합 (서버)
```js
const { Pool } = require('pg');
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
const selfEvolve = require('./server/selfevolve')({ pool });
app.use('/se', selfEvolve.router);         // POST /se/events, GET /se/report?domain=stock&days=7
```

## 통합 (앱 · Android)
```kotlin
val se = SelfEvolveClient(context, baseUrl = "https://server/se", domain = "stock")
se.log("signal_view", context = "youtuber_bongseon")   // 신호 봄
se.log("order_try", context = "buy", ok = false)        // 사려다 실패 = 니즈
// onStop: se.flush()
```

## 통합 (컴퓨터/웹)
```html
<script src="clients/selfevolve-web.js"></script>
<script>
  const se = SelfEvolve({ baseUrl: 'https://server/se', domain: 'stock' });
  se.log('video_watch', { context: 'youtuber_세력주돌려차기' });
  se.log('backtest_run', { context: 'strategy_X', ok: false });
  window.addEventListener('beforeunload', () => se.flush());
</script>
```

---

## 도메인 적용 예시

### 📈 주식 (stock)
- 수집: 어떤 유튜버/시그널을 자주 보나(`signal_view` context=유튜버명), 매수 시도/실패(`order_try` ok), 백테스트 실행/실패, 종목 검색.
- 니즈 발굴: "봉선추양사부·세력주돌려차기 시그널을 자주 보지만 **실행(매수)엔 실패/주저** → 원클릭 실행·자동 알림이 니즈."
- 진화: 자주 보는 유튜버·전략을 자동 요약/알림, 실패 지점 개선. (영상 분석은 **도메인 애널라이저 플러그인**으로 추가 — 엔진은 수집·집계·니즈발굴 담당.)

### 👵 실버앱 (silver, 노인 전문)
- 수집: 어떤 화면에서 **오래 머물다 이탈**(`screen_stuck` ok=false), 글자 확대/음성 요청, 완료 못 한 동작.
- 니즈 발굴: "노인이 결제·예약 화면에서 반복 실패 → 더 큰 버튼·음성 안내·보호자 연동이 니즈."
- 진화: 실패 많은 화면을 자동으로 단순화 후보로 올림.

---

## 정직 경계 (구라 금지)
- 엔진이 자동화하는 것은 **수집·집계·니즈발굴·리포트**다. 이게 진짜 "자기진화"의 실체.
- **영상 분석**·**자가 코드작성**은 엔진이 스스로 하지 않는다 — 도메인 애널라이저(예: 주식 영상 파서) 또는 추후 LLM이 담당하는 별도 플러그인.
- 없는 지능을 있는 척하지 않는다. 데이터·플러그인·API가 준비된 만큼만 동작.

## IP
[특허후보·별도] 도메인 독립 "미발화 니즈 발굴 자기진화 루프" 프레임워크 — 콜레이더 출원과 별개로 상위(포괄) 청구 검토(변리사). 각 도메인 적용은 파생.
