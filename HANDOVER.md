# 콜레이더(CallRadar) 인수인계 — 2026-08-03

> 새 세션은 이 문서 + `C:\CallRadar\CLAUDE.md`(매 세션 자동로드)를 먼저 읽을 것.
> 이 문서 = "지금까지 한 일 + 열린 작업 + 접속정보 + 도메인규칙" 총정리.

---

## 0-AA. 최신 인수인계 (2026-08-14 — 이 섹션 먼저 읽기)

**앱 v62 / 2.6.2 (versionCode 62) 빌드완료·미업로드.** 커밋 앱 `c87b83f`, `_releases/v62/`(APK+mapping-v62). 서버 라이브 `6a71b10`.

**오늘 한 일 (v60~v62 + 서버):**
- **서버(배포됨)**: ① 콜제보 매출집계 홈기준 제외 통일(stats/daily·monthly·platform·comparison·insights) ② 레이더 GPS 광역 지역분류 `cr_region(lat,lng)` + hotzone/dest-risk/patterns에 region 태깅·`?region=` 필터(서울/인천/경기/강원/충북·충남/전북·전남/경북·경남/제주시·서귀포시) ③ 기능감사 엔드포인트 `/api/usage/audit`. (커밋 867cede, 6a71b10)
- **v60/2.6.0**: 근무카드 오늘매출 아래로 이동 + 세션거리 수동 초기화(548km 대응, 이동거리 박스 탭).
- **v61/2.6.1**: 공항 '예상혼잡도'에 **"앞으로 도착 손님(30분 단위·향후 3시간)" 예측 카드** — 도착항공편 도착시각+인원 버킷팅, 이동시간칩(30/45/60) 강조. "내가 도착할 때" 판단. 정보추가 아닌 재구성.
- **v62/2.6.2**: **심플 홈(카카오식 무탭) 옵트인 모드** 신규 — `SimpleHomeScreen.kt`·`SimpleMenuScreen.kt`, MainActivity `home_mode` 분기+`SimpleMain` 라우팅, MoreScreen '홈 모드(베타)' 토글. classic 무손상. **상세·남은일=`C:\CallRadar\심플홈_리디자인_스펙.md`.**

**기능 사용 감사 결과(90일, /api/usage/audit·testers-data):** 등록 314·운행유저 77·7일활성 56·매출 1.26억. 기록수단=카카오T(자동,41명)+길빵/예약(수동,60명 최다)+우버(25). 화면도달률 홈100·레이더57·기록56·**공항49**·정산21·명세서19·분석16·궤적15·랭킹14·AI8·예약5·이벤트4·노하우3. **콜제보=죽은기능**(platform 'callreport' 6건/1명=테스트, '콜제보' 값 아예 없음, UI엔 이미 없음). MoreScreen `CORE_ONLY=true`로 예약·이벤트·노하우·AI비서 이미 숨김. **우버 요금 0원 파싱 이슈 실재**(user 468, 구버전 2.5.3, 우버 END_SCREEN이 홈화면이라 파싱 실패).

**열린 작업(다음 세션):** ① **심플모드 하위화면 B 리스킨**(현재 기존화면+B뒤로가기헤더로 연결만) + 홈4칸 편집 UI + B메뉴 커스터마이즈 (스펙파일 참조) ② **우버 0원 파싱 보정**(로그 원문으로 현행 우버 UI) ③ 원스토어 v62 업로드(대표). ④ 레이더 시별분류(경기/인천은 폴리곤 필요, 광역까지만 됨).

**실기기 검증 필요(중요):** v62 심플모드는 컴파일·빌드만 통과 — 출근/퇴근/일시정지·모드전환(recreate)·4카드 라우팅·뒤로가기를 **대표 폰에서 실제 확인** 필요. 문제 시 더보기›홈 모드 토글로 즉시 classic 복귀.

---

## 0-A. 최신 인수인계 (2026-08-13 — 이 섹션 먼저 읽기)

**현재: v59 / 2.5.9 빌드완료·본폰(R5KL) 설치·검수 제출 예정.** 커밋 3741343, `_releases/v59/` 보관. 서버 최신 라이브 d0a13b7. 다음 versionCode 60+.

**오늘 v55→v59 (전부 빌드·보관·대부분 검증 완료, 원스토어 미업로드):**
- v55: 요금 총액 오긁기 방지, 콜취소 후 플로팅 먹통(탭=취소).
- v56: 자동기록 토글 OFF 실작동(auto_free_open이 토글 덮던 버그, auto_record_touched 도입), 티머니 알림도 notif_capture_on 게이트, 근무세션 요약 서버저장(/api/work-session/close, 서버 141d729).
- v57: 토글 3개→1카드, 통행료→지출 자동분리(매출=미터만), 자정 날짜귀속(완료시각+day_start_hour, **옵트인**=근무카드 '영업일 시작시각' 설정해야 활성). 서버 4808a88(PUT trips business_date).
- v58: 홈·기록·월별·달력·공유 매출 통일=fare+팁+프로모, 보너스(프로모·호출료) 별도표기. 서버 d0a13b7. **서버 실증: 홈=월별=기록 일치 확인.**
- v59: 근무 일시정지 버그(20초 투폰 pull이 로컬 일시정지 덮어써 재개→로컬변경 후 30초 가드), 이동거리 폭주(속도 40m/s·단일 3km). 폰 검증: 일시정지 정지 확인.

**열린 작업(우선순위):** ① 레이더 지역분류(서버만: 서울=전체/인천·경기 시별/충청·경상·전라 남북 시별/제주 제주·서귀포) ② 홈 근무카드 위로(300줄 리팩터, 회귀위험) ③ 548km 등 과거 오염 근무기록(새 영업일 리셋 or '세션 거리 초기화' 버튼 추가) ④ 콜제보 매출집계 일관성(홈 제외/기록·월별 미제외) ⑤ 학습정확도 채점버그, 영수증OCR 실검증+가스학습 ⑥ 위치정보 신고(사업자등록 후) ⑦ 사업지원: 예비창업패키지(2027 상반기, 최대 1억, 사업자 없어야 자격)—`C:\CallRadar\예비창업패키지_사업계획서_초안.md`, 원스토어 위치정보 문의메일—`원스토어_위치정보_문의메일.md`.

**정리:** 이 방(세션)이 너무 길어 렉 → 루트 임시파일 228개 `_scratch/`로 이동, CLAUDE.md 압축. 새 방에서 이어감.

---

## 0. 한눈에 — 현재 상태 (2026-08-03 #2 세션 갱신)
- **★[치명버그 발견·수정·검증 v38] 원스토어 자동화 GPS 소스 누락**: `LocationTrackingService`(자동기록의 GPS 좌표 currentLat/Lng 소스)가 활성 매니페스트에서 빠져 있어, 접근성 `NaviIntentReceiver`가 이 서비스를 못 켬(logcat "not found") → 좌표=0 → `extractTaxiInfo`가 'GPS 좌표없음'으로 **자동 트립을 아예 못 만들던 상태**. → `src/onestore/AndroidManifest.xml`에 `LocationTrackingService` 선언 추가(자동화가 onestore 전용이므로 거기만). **실기기 검증**: 접근성 켜니 `LocationTrackingService`가 isForeground=true(채널 callradar_location)로 정상 실행됨. **원스토어는 반드시 v38 올릴 것**(v37 이하 onestore 자동화는 GPS 없어 트립 생성 안 됨). (BootReceiver도 활성 매니페스트에 없음=부팅 자동시작 미동작 — 참고.)
- **★[관리자 게이트 추가·검증 v38] 자동기록은 관리자 기기에서만**: 오픈 배포된 onestore라 아무 유저나 안드로이드 접근성에서 켜면 자동기록이 돌던 상태 → **관리자 해금(is_admin) 기기에서만 동작**하게 게이트. 구현: `NaviIntentReceiver`가 `is_admin` pref 없으면 onAccessibilityEvent 즉시 return + LocationTrackingService 미시작. 해금 UI: **홈 헤더 버전 라벨 탭 → "관리자 인증" 다이얼로그 → ADMIN_KEY 입력 → `POST /api/admin/verify`(서버 대조) → 이 기기 is_admin=true**. 키는 앱에 저장 안 함(서버만 대조). **실기기 검증**: 미해금 시 접근성 켜도 LocationTrackingService 안 뜸(무동작), 버전탭 다이얼로그 정상, 잘못된 키 403. (해금 후 정상동작은 실제 ADMIN_KEY 필요 — 대표가 확인.) `is_admin` pref는 기기별 로컬(기기마다 1회 해금). 해제: 같은 다이얼로그의 '이 기기 관리자 해제'.
- **★[개인/법인 사납 분리 버그 수정 v39] 개인택시인데 고정비 120,000원**: 제보자=개인택시(서울) 계정인데 가스 ~18,000만 넣었는데 퇴근 근무영수증에 "고정비(유류+사납) −120,000"이 뜸. 원인: 홈 메인 카드는 4-A로 driver_type 게이트됐지만, **퇴근 근무영수증의 `sumFixedCost`(HomeScreen ~798)만 `daily_sanap`을 driver_type 무관하게 그대로 더함.** 120,000은 사납 다이얼로그 프리셋 칩(0/10만/**12만**/15만) 중 하나가 예전에 저장돼 개인 전환 후에도 잔존한 것. 기사설정은 법인/개인이 **물리적으로 분리 저장 안 됨**(모두 같은 prefs, driver_type은 플래그) → 게이트 안 된 읽기에서 누출. 수정: (1) 근무영수증 `effShiftSanap = if(corporate) daily_sanap else 0`로 게이트, (2) 기사유형을 **개인으로 저장 시 `daily_sanap`=0으로 초기화**(잔존 법인값 제거, MoreScreen 유형 다이얼로그). 전 앱 `getInt("daily_sanap")` 재점검 → 나머지는 모두 게이트됨(홈 메인/월). **양 스토어 공통 코드라 Play·원스토어 둘 다 v39 필요.** (제보자는 v39 설치 시 읽기게이트로 즉시 정상, 유형 재저장 없이도 고정비=가스만.)
- **[자동기록 시작/종료 토글 v38]** 앱이 자기 접근성 서비스를 코드로 못 켜므로, **접근성은 켜둔 채 인앱 토글(`auto_record_on`)로 실제 기록 on/off.** `NaviIntentReceiver`는 `isAdmin() && autoOn()`일 때만 동작(둘 중 하나라도 off면 무동작 + 위치서비스 미시작/중지). UI: **홈에 "🤖 자동 기록 (관리자)" 스위치 카드** — 관리자 해금 && onestore flavor일 때만 노출. 켜면 접근성 미허가 시 접근성 설정 열고 안내, 허가돼 있으면 LocationTrackingService 시작. 끄면 자동기록 종료 + LocationTrackingService 중지. (카드 실물은 관리자 해금 필요 → 컴파일·게이트만 검증, 대표가 키로 확인.)
- **자동화 요금파싱은 미검증**: NaviIntentReceiver는 카카오T/우버/티머니고 화면의 특정 한글 문구(결제요금/미터요금/탑승완료/손님이 직접결제 등)를 하드코딩 파싱 → 택시앱 UI 변경 시 깨짐. 실주행+`GET /api/debug/logs/{userId}`(END_SCREEN·FARE_CACHE 원문)로만 현행 검증 가능. 다음 세션 최우선 후보. (서버 커밋 `56cf924`=admin/verify 추가.)
- **앱 버전: 현재 빌드 = v40 / 2.4.0 (versionCode 40).** 원스토어 APK=`app/build/outputs/apk/onestore/release/app-onestore-release.apk`(→`2.4.0-onestore`), 플레이 AAB=`app/build/outputs/bundle/playRelease/app-play-release.aab`(→`2.4.0`). 둘 다 개인/법인 사납 버그 픽스 포함. 다음 업로드는 41+.
  - **원스토어는 v38 이상 필수**(v37 자동화 GPS 깨짐). 지금은 v40으로 통일. 실기기(R3CW70QB0NY) 설치·기동 확인됨.
  - v37 추가(궤적 4종): **레이더 지도 🧭 궤적 온/오프 토글**(RadarScreen FAB + DriverMapScreen showTrack), **실차/공차 거리 정확도 수정**(LocalTrackDatabase statsSince 400m컷→순간속도, 20초간격 고속주행 누락 해결), **과거 날짜 조회**(TrackActivity ◀▶ 날짜이동 + pointsBetween/statsBetween, 궤적 보관 7→31일). ⚠️ 지도 렌더는 릴리스에서만.
  - v36 추가: **① 레이더 지도(카카오맵)에 오늘 근무 궤적 폴리라인**(DriverMapScreen `drawTodayTrack`, 실차 파랑/공차 회색+마커). ⚠️ 디버그는 카카오맵 인증실패로 안 보임 → 릴리스에서만. try/catch 가드.
    **② 홈 금액 중복 정리**: 법인 홈에서 '예상 기사몫'과 '예상 월급 수령액'이 같은 금액 2번 뜨던 것 → 기사몫 카드를 큰 금액 빼고 '회사 급여 계산기 바로가기'로. 금액은 월급 카드 1개만. (실기기 검증 완료)
    **③ 플로팅 100m 필터 개선**: 100m 미만이어도 운행 2분+면 기록(정체·단거리 보호), 둘 다 짧을 때만 폐기. `MIN_RIDE_MS=2분`.
    **④ 플로팅 취소 단계 명확화**: 종료 후 취소창 3s→5s, 버튼 "취소?"+안내 토스트, 기록 후 "기록탭 삭제" 안내.
  - (v35=유저버그 3건 수정, v34=그 전. #1 '사납 남은금액 카운터'는 대표 요청 보류.)
  - AAB 고정 경로: `C:\CallRadar\app\build\outputs\bundle\playRelease\app-play-release.aab` (`_store` 복사 금지).
  - 보조폰(R3CW70QB0NY): v34까지 설치·검증. v35 디버그는 폰 재연결 후 재설치 필요(테스트 중 USB 분리됨).
- **서버: Render 라이브** (github `sollbe82-droid/callradar-server` → 자동배포). 최신 커밋 `c05413e`(지출수정 PUT). 이전 `4e57a42`(레이더 학습시드).
- **DB 자동백업: GitHub Actions 매일 03시(KST)** 작동 확인됨(대표가 `DATABASE_URL` 시크릿 등록).

---

## 0-1. 2026-08-05 세션 로그 (이 방에서 다룬 것 총정리)
**한 일**
- v38: 자동기록 관리자 게이트 + **인앱 시작/종료 토글**(auto_record_on) 구현·빌드. 안드로이드는 앱이 자기 접근성서비스를 코드로 못 켬 → "접근성은 켜둔 채 pref 토글로 실제기록 on/off" 설계. 홈 "🤖 자동 기록(관리자)" 스위치(관리자+onestore일 때만 노출).
- v39: **개인/법인 사납 분리 버그 수정**(위 ★항목). 근무영수증 고정비 게이트 + 개인전환 시 daily_sanap=0. Play AAB + 원스토어 APK 빌드.
- v40: 스토어 업로드용으로 버전만 40/2.4.0으로 올려 재빌드(둘 다). 실기기 versionCode=40, 2.4.0-onestore 확인.

**대표 Q&A로 확정된 지식 (중요)**
- **어드민키(ADMIN_KEY)**: 앱·코드에 저장 안 됨(의도). **Render 서버 환경변수 `ADMIN_KEY` 값**이 곧 어드민키. 서버 `/api/admin/verify`가 대조만. ⚠️ **Render에 이 변수가 설정 안 돼 있으면 관리자 해금이 무조건 403**. 대표가 Render Environment에서 존재 확인/설정 필요. 해금은 홈 상단 버전 라벨 탭 → 입력.
- **원스토어 업데이트 방식**: 플레이(AAB/트랙)와 달리 **개발자센터(dev.onestore.co.kr) → Apps → 신규 바이너리(APK) → APK 직접 업로드 → 출시노트 → 검수 제출.** 판매정보 안 건드리면 유지. versionCode는 이전보다 커야 함. 같은 릴리스 키(callradar-release.jks) 서명 필수. 검수 1~2일.
- **구글 플레이 프로덕션 잠김 상황**: 대표 계정=개인(personal, 2023-11-13 이후) → **프로덕션 출시하려면 비공개 테스트(Closed) 테스터 12명·14일 선행 필수.** 공개 테스트(Open)로는 프로덕션 권한 안 열림(트랙 잘못 고르면 안 됨).
- **"프로덕션 신청 했는데 공개테스트 심사래"**: 정상 흐름. [프로덕션 액세스 신청]은 **질문지 제출 → 구글이 권한 줄지 심사(평일 3일, 최대 7일)** 하는 단계(출시 아님). "프로덕션 액세스 권한 부여" 메일 후에야 프로덕션 탭에서 라이브러리로 버전 추가 → 다시 **출시 심사(~7일)**. 전체 20~30일.
- **"지금 버그 바로 업뎃 안 되잖아?"** → 맞음. 구글 프로덕션은 권한 전까지 잠김. **실사용자는 원스토어에 있으니 버그픽스는 원스토어 v40으로 즉시 배포.** 플레이는 **비공개 테스트 트랙에 v40 AAB 올려두면 → 라이브러리에 적재 → 권한 나오는 즉시 원클릭 프로덕션 전환**(재빌드 불필요).
- **근본해결**: 사업자 등록 → **구글 조직(Organization) 계정**은 비공개 테스트 면제. 장기적으로 권장.

**다음 방에서 이어서 할 것 (열린 작업)**
1. **원스토어 v40 업로드**(버그픽스 실배포) — 대표 진행. 출시노트 필요하면 작성.
2. **플레이 비공개 테스트 트랙에 v40 업로드** + 12명/14일 유지 + 권한신청 질문지. (권한 오면 프로덕션 전환)
3. **Render `ADMIN_KEY` 설정 확인** — 안 돼 있으면 자동기록 관리자 해금 불가.
4. **자동화 요금파싱 실검증**(미완): 관리자 기기 실주행 → `GET /api/debug/logs/{userId}` 원문으로 카카오T/우버/티머니고 현행 UI 파싱 확인·보정. (택시앱 미설치라 이번에도 미검증)
5. (보류) #1 사납 남은금액 카운터.
- **해결됨(이번 세션): 4-A 개인택시 홈 버그 / 4-D 레이더 시드 / 궤적 라이트다크·미작동 / 더보기·기록 스크롤 보존 / 회사프로필 자동시드 제거.**
- **해결됨(유저버그 3건, v35): ① 기록탭 수정/삭제 시 UI 튐(loadData 스피너 → 조용한 갱신) ② 지출 수정 기능 추가(서버 PUT `/api/expenses/:id` + 항목 탭 편집) ③ 근무세션 km ~60km 정지(WorkSessionService 하드 400m 컷 → 순간속도 필터).**
- **미완 최우선: ① ENFORCE_TOKEN 켜기(4-B) ② v34 실기기 테스트(4-C) ③ 궤적 미작동 "1번"(플로팅 버튼만 켜도 기록) 적용 여부 결정.**

---

## 1. 최우선 열린 작업 (다음 세션 TODO)

### 4-A. ✅[해결 2026-08-03 #2] 개인택시 홈화면 버그 (제보자 mentor5685@gmail.com)
- **해결 요약**: 근본원인 = `CompanyProfile.all()`이 앱 최초 실행 시 예시 시드(다연상운·서원택시)를 모든 유저 로컬에 자동 주입하고, `active()`가 미선택 시 `firstOrNull()`로 첫 프로필 반환 → 홈 "예상 기사몫" 카드가 driver_type을 안 보고 그대로 표시. 개인택시(personal)에 법인 사납/기사몫이 뜸.
  - 수정①(HomeScreen.kt): 기사몫 카드를 `isCorporate`일 때만 표시. "오늘 매출" 순수익도 개인택시는 사납 미적용(effDailySanap=0).
  - 수정②(CompanyProfile.kt): 자동 시드 주입 **제거**(기사가 직접 추가/명세서 스캔한 것만). 기존 폰의 손 안 댄 잔여 시드는 `maybeClearLegacySeeds()`로 1회 자동 정리(수정/추가한 건 보존).
  - (참고: 회사 프로필은 로컬+본인계정 백업 범위. 서버·타 유저 공유 경로 없음 = 데이터 유출 아님.)
- (이하 원 진단 기록 — 참고용)
- **증상(스샷 3장)**: driver_type을 **개인택시**로 설정한 유저인데, 홈에 **법인 기사몫 계산이 뜸**.
  - 홈: "다연상운·일차·예상 기사몫 214,500원 / 총매출 336,300 − 사납 110,000 − 가스 11,800 / 8월 예상 순수익 324,500 / 순수익 −11,800".
  - 내 프로필: **회사 프로필 2개**(다연상운·일차 사납11만·가스기사부담[활성], 서원택시·주간 사납14만·가스회사부담).
- **문제 분석(가설)**: 개인택시는 **사납금이 없음**(본인 차량). 그런데 회사(법인) 프로필이 활성화돼 있어 사납/기사몫 공식이 그대로 적용됨. 즉 driver_type=개인인데 CompanyProfile(법인) 계산이 홈을 지배.
  - 확인 포인트: (1) driver_type이 실제 '개인'으로 저장됐는지(users/driver_settings), (2) 홈이 driver_type을 보고 개인택시면 사납/기사몫 카드를 숨기고 "매출−경비" 순수익만 보여야 하는데 그렇지 않음. (3) "순수익 −11,800"은 매출 0인데 가스만 빠져서 음수 — 오늘 매출 0일 때 표시 로직도 점검.
- **해야 할 것**: 개인택시(driver_type=personal)일 때 홈/기사몫 카드 로직 분기 — 사납 미적용, 순수익=매출−경비(가스+지출), 세무는 개인택시 기준. HomeScreen의 CompanyProfile 계산부(예상 기사몫/월급) + CompanyProfile.kt 확인.
- **유저 데이터 조회**: 서버에서 이메일→user_id 찾기. `/api/admin/overview?key=<ADMIN_KEY>`는 집계만. 개별은 DB 직접 or 앱 로직으로. (제보자 계정 실데이터로 재현 권장.)

### 4-B. ENFORCE_TOKEN 켜기 (전역 무토큰 IDOR 완전차단)
- 현재 OFF. 서버 코드/미들웨어는 준비됨. Render 환경변수 `ENFORCE_TOKEN=true`로 켜면 됨.
- 켜기 전 조건: **무토큰 요청 비율 ~0% 확인**. `/api/admin/token-stats?key=<ADMIN_KEY>`로 확인(현재 계속 0%). 매일밤9시 자동점검(스케줄러 `callradar-daily-check`)이 안전신호 알려줌.
- 리스크: v24 미만 초구버전 앱은 토큰 안 보내 로그아웃될 수 있음(현재 트래픽 0%라 사실상 안전).

### 4-C. v34 실기기 테스트 + 스토어 심사통과 확인
- 보조폰(R3CW70QB0NY, 삼성)에 v34 디버그 설치·정상 기동 확인됨. 대표가 로그인·기록·지출·**하루기록공유**·오프라인큐·**개인택시 홈·운행궤적·더보기/기록 스크롤** 실동작 확인 필요.
- 주의: 보조폰 기존 설치본은 **디버그 서명**("지도 인증 실패" 정상). 릴리스 APK는 서명 충돌로 덮어설치 불가 → 디버그 APK(`assemblePlayDebug`)로 덮거나, 기존 삭제 후 릴리스 설치(데이터 초기화). adb: `C:\AndroidSdk\platform-tools\adb.exe`.

### 4-D. ✅[해결 2026-08-03 #2] 레이더 카톡 시드 블렌딩 (실시간 콜드스타트 블렌딩 배포됨)
- 결정: **실시간 블렌딩 + 콜드스타트 보조**로 확정·구현·배포(커밋 `4e57a42`).
- 구현: `server/radar_seed_kakao.json`(2,053건) + `server/radarSeed.js`(요일×시간 집계, seedWeight=K/(K+appCalls), K=8) → `radar2.js`의 `/api/radar/patterns`가 기존 `heat`(앱 실측, 무손상) 유지하며 blended `demand` 배열 추가 반환. `blended`/`seedMeta` 필드 포함. area 필터 시·`?seed=0`이면 미적용.
- 라이브 확인: `/api/radar/patterns` → blended=true, demand 168칸, 콜드스타트 칸(앱 0건인데 시드>0) 노출. 금액·효율엔 미사용(시드에 돈·좌표 없음).
- 후속(선택): 앱(RadarScreen)이 `demand` 필드를 실제 UI에 소비하도록 연동. hotzone(출발셀) 블렌딩은 시드 지역태그 불확실로 v1 미포함.

### 4-E. 나머지 중·저위험 (A-Z 감사 후속, CLAUDE.md에도 기록)
- 앱: QuickEntry 팁/프로모 오프라인 유실(M6, 트립 동기화 경로라 신중), MeterActivity 지역 stale(재미기능), 화면별 에러상태 UI(SEV4, 대공사), 타임존 tolerant 파싱.
- 서버: authlogin 회원가입 동시성 409(폐기된 기능), 길드 초대코드 충돌/조인 TOCTOU.

---

## 2-B. 2026-08-03 #2 세션 완료 (v34/2.3.4, 커밋 4e57a42)
- **[앱] 4-A 개인택시 홈 버그** — HomeScreen 기사몫 카드/사납 개인택시 차단, CompanyProfile 자동시드 제거 + 잔여시드 1회 정리(위 4-A).
- **[앱] 운행 궤적 라이트/다크 + "지도 안 뜸"** — `TrackActivity`가 앱 테마(`AppTheme`/`dark_mode`) 무시하고 다크 하드코딩 → 화면 색·캔버스 배경·범례 전부 테마 대응. onCreate에서 `AppTheme.isDark` 동기화.
- **[앱] 더보기·기록탭 스크롤 위치 보존** — 하위화면/수정 후 뒤로가면 맨 위로 튀던 것. MoreScreen: 스크롤 상태를 상위로 hoist해 MoreGrid/List에 전달(route 변경에도 유지). RecordsScreen: 메인 리스트 스크롤을 `isLoading` 토글 위로 hoist.
- **[앱] 운행 궤적 미작동 검토 + 2·3번 수정** — 원인: ①출근(WorkSessionService) 중에만 기록(플로팅 버튼과 별개) ②`onNewLocation` accuracy>50m 컷이 '대략적 위치'(coarse) 점을 전량 폐기 ③권한/절전 ④dayStart 필터 ⑤2점 미만. → **수정 2번**: 필터 50m→150m(coarse도 기록). **수정 3번**: TrackActivity 빈 화면에 사유별 안내(권한없음/정밀꺼짐/출근안함/오늘점없음). **1번(플로팅 시 기록)은 배터리·정책 고려해 보류(대표 결정 대기).**
- **[서버] 4-D 레이더 학습시드 v1** — 실시간 콜드스타트 블렌딩 배포(위 4-D).
- **빌드**: compilePlay(Debug/Release)Kotlin BUILD SUCCESSFUL. 릴리스 AAB 서명 완료(고정 경로). 보조폰 디버그 설치·기동 확인.

## 2. 이번 세션에서 완료한 일 (2026-08-03 #1 — 전부 배포/빌드 검증됨)

### 서버 (Render 라이브)
- **보안/IDOR**: admin 4종+radar overview → ADMIN_KEY 게이트, `/api/pair/create` 로그인강제, `/api/pair/merge` 소유권+트랜잭션, `/api/users/merge` 관리자전용+트랜잭션, `/api/auth/token` 계정탈취 차단, `/api/auth/whoami`·`/api/auth/logout` 추가, 보안헤더(nosniff/frame/referrer).
- **데이터안정**: pool.on('error')+unhandledRejection, 일일마감 트랜잭션화(+가스0 잔여LPG 버그수정), import/bulk·지출·영수증 client_uuid 멱등, 자동운행 중복방지.
- **스키마/백업**: users/trips/receipts + guilds/guild_members/call_logs + points/total_trips CREATE 보강(재해복구), GitHub Actions 일일 pg_dump.
- **KST 정합**: stats/daily·insights(주중주말 일별집계)·streak(야간자정)·deadzones·radar hotzone(ended_at 이중변환)·랭킹 주간·세무 연도경계, 개인택시 부가세 면세.
- **★플랫폼별 매출 자정 버그(재발 제보)**: `/api/stats/platform`이 영업일(dayStart) 미보정 → 서버가 유저 day_start 직접 읽어 보정(앱 업데이트 불요, 통제테스트 PASS). 대시보드 XSS 이스케이프, /api/trips 페이지네이션, 오래된 토큰 정리, 요금 coerce, rate-limit XFF, airport P02→P03 등.

### 앱 (v33 AAB)
- 오프라인 **지출 큐잉**(local_expenses+멱등키, 전송먼저→실패시 로컬), LocalTripDatabase onUpgrade DROP제거(운행 유실방지), 딥링크 whoami 검증, cleartext off, Cloudinary 토큰분리, MediaProjection FGS 제거(Play정책).
- **크래시 수정**: WorkSessionService 위치권한 없을때 FGS 크래시, 영수증/명세서 갤러리 **큰사진 OOM**(백그라운드 다운샘플).
- **무한스피너 해결**: Records/Stats/Ranking/Home/More/Radar 읽기 readTimeout 전면 추가, 홈 법인월급 콜드스타트 과대표시(mwd 캐시), 알림ID 충돌(3105).
- 운행 저장/수정/삭제 responseCode 확인+실패토스트(유실방지), 홈 15s→60s 폴링.
- **★신기능: 하루 기록 공유**(이미지 카드, 금액 제외, 기록탭+달력 두 곳 버튼). shareDayRecordsImage(RecordsScreen.kt).

---

## 3. 접속·경로·빌드 (정관 핵심 — CLAUDE.md 참조)
- 앱 소스 `C:\CallRadar\app`, 서버 `C:\CallRadar\server`(GitHub sollbe82-droid/callradar-server → Render callradar-server.onrender.com).
- 빌드: `set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"` → `gradlew.bat :app:bundlePlayRelease`(AAB) / `assemblePlayDebug`(실기기 테스트용). SDK `C:\AndroidSdk`.
- 서버 로컬검증: Windows Node(`node -v` v24, 인터넷됨)로 스크립트 실행 / 샌드박스는 onrender 접속 불가.
- 배포: `cd C:\CallRadar\server && git add ... && git commit -m 하이픈메시지 && git push origin main` (공백메시지 cmd 깨짐 주의). Desktop Commander(Windows git) 사용.
- 문서생성/카톡파싱 등은 샌드박스 python 가능(pip --break-system-packages).

## 4. 크리덴셜/환경 (민감 — 취급주의)
- **ADMIN_KEY = `90d96600658fbf204a3032a69455e8b8`** (Render에 이미 설정). 대시보드: `callradar-server.onrender.com/admin/회사명?key=<ADMIN_KEY>`.
- Render env 전부 설정됨: DATABASE_URL, KAKAO_REST_KEY, KAKAO_REDIRECT_URI, AIRPORT/DATA_GO_KR/OPINET/SEOUL/KOPIS_API_KEY, PORT. (ENFORCE_TOKEN만 의도적 미설정.)
- 릴리스 키스토어: `C:\CallRadar\callradar-release.jks` (alias callradar). local.properties에 KAKAO_NATIVE_KEY·키스토어 비번.
- GitHub 저장소 시크릿 `DATABASE_URL` 등록됨(일일백업용). Play App Signing으로 스토어 배포본은 재서명됨(로컬 릴리스 서명과 다름 → 보조폰 덮어설치 충돌 원인).

## 5. 도메인 규칙 (반복 실수 금지 — CLAUDE.md와 동일)
- **한국 택시앱(카카오T·우버 등)은 콜/운행완료 알림 없음** → 알림리스너 자동기록 금지.
- 기록수단: 플로팅 운행버튼 / 기록탭 수동추가 / 정산 가져오기. 미터기는 재미·추정용(저장 안 함).
- **꼼꼼히**: "완료"는 컴파일/빌드 통과·검증 후에만. 문제 나오면 다 고치고 재검증·재검토.
- 로그인: 카카오+게스트만(아이디/비번 폐기).
- **개인택시는 사납금 없음**(4-A 버그 핵심).

## 6. 참고 데이터·산출물
- `C:\CallRadar\radar_seed_kakao.json` — 카톡 팀 콜 시드(2,053 이벤트).
- 카톡 원본 업로드: uploads 폴더(세션 종료 시 사라짐 → 필요시 재업로드).
- 스케줄 작업: `callradar-daily-check`(매일 21시, 서버 헬스+무토큰비율 점검).
