# 콜레이더(CallRadar) 프로젝트 정관 — Claude 필독

> 이 파일은 매 세션 자동 로드된다. 여기 적힌 규칙·사실을 어기지 말 것.
> 상세 인수인계·접속정보·이력은 `C:\CallRadar\HANDOVER.md` 참조.

## ★ 대표 신상/사업 (반복 실수 금지)
- **대표는 "법인 기사"(법인택시 일차 도급 운전직) = 직업.** 개인택시 아님(홈/레이더 개인택시 로직과 혼동 금지).
- **콜레이더 앱은 아직 사업자등록 없음 — 일부러 안 냄.** 나중에 국책 개발자 지원사업으로 등록 계획. 위치기반서비스사업 신고 등 "사업자등록 필요" 절차는 그 이후 단계. 지금 "사업자등록 있다/내라" 가정 금지.
- **위치정보법(원스토어 심사)**: 앱이 GPS 궤적(track_points)·운행좌표를 서버 저장 → '위치 전송' 표기 시 위치기반서비스 신고필증 필요. '미전송' 허위표기는 법적책임+판매중지 리스크. 코드로 좌표 제거는 레이더 핵심 죽여서 제외. 결론=사업자등록 되는 대로 '전송'+신고. 문의: 위치정보지원센터 02-588-0185.

## ★ 도메인 사실 (반복 실수 금지)
- **한국 택시 플랫폼(카카오T·우버·타다)은 콜/운행 완료 "알림"이 없다** → "알림 읽기로 운행 자동기록" 방식 절대 제안·구현 금지.
- 접근성 자동기록은 **원스토어 전용**(Play는 심사 거부·삭제 위험이라 뺌).
- 실제 기록 수단: (1) 플로팅 운행버튼(직접 탑승/완료), (2) 기록탭 수동추가, (3) 플랫폼 정산 가져오기(가장 정확). 미터기는 재미·GPS추정용, 기록 저장 안 함.
- 미터기 심야할증(서울 2023.2~): 22~23시 20%, 23~02시 40%, 02~04시 20%.

## ★ 스토어 구분 (원스토어 vs 플레이) — 반드시
- flavor 2개: `play` / `onestore` (`app/build.gradle.kts` 분기).
- **onestore = 접근성 ON = 플랫폼 자동판별+GPS 자동기록.** play = 접근성 OFF(플로팅 수동). "플레이에도 자동기록" 제안 금지(정책상 뺀 것).
- 카드승인 알림 금액 자동입력(CallCaptureService/TmoneyNotificationService, NotificationListener)은 양쪽 노출. 카드 알림엔 금액만·플랫폼 없음.

## ★ 작업 원칙
- 꼼꼼·세심. **추측으로 "다 됐다" 금지.** "완료"는 실제 컴파일/빌드 통과 확인 뒤에만 보고.
- **아이디어 들으면 바로 빌드 금지** → (1)생각 (2)코드검증 (3)가상시뮬(엣지케이스) (4)역제안까지 먼저 주고, 합의 후 구현.
- **★★ 빌드/업로드 직전 체크리스트**: 요청·논의 항목을 목록으로 정리해 "이거이거 넣습니다/빠진 것 없나요?" 먼저 확인 후 빌드.
- 같은 지적 반복 금지. 정관 먼저 확인하고 답할 것.

## ★ 반복 금지 교훈 (꼭 지킬 것)
- **① 시간대**: 서버 로그·DB는 전부 UTC → 사람에겐 **KST(+9) 환산**해서 말한다.
- **② 유저 보고를 먼저 믿어라**: "서버 정상 ≠ 클라 정상." 배지·activeTripId·플로팅/화면 상태까지 확인.
- **③ 자동기록/근무 버그는 '상태머신'부터**: 화면파싱 곁가지 말고 트립·세션 생명주기(lastTripId/activeTripId, pause/finalize) 추적.
- **④ 버전 올릴 때마다 git 커밋(필수)** — 회귀 시 diff 위해.
- **⑤ 빌드는 Claude가 직접**: 리눅스칸은 SDK없음·저장소차단이라 컴파일만 못함. `.bat` 만들어 **파일탐색기(computer-use)에서 실행**(주소창 타이핑 막히면 **더블클릭**), `C:\CallRadar\*.log`를 Read로 `BUILD SUCCESSFUL` 확인. 서버 배포도 `.bat`로 `git -C C:\CallRadar\server push`.
- **⑥ 결론은 검증 후에만.** 로그·코드 확인 전 "원인 확정/다 됐다" 금지.

## ★ 진단 도구
- **★ 관리자 접근 방식 변경(2026-08-27) — `?key=` 는 이제 막혔다(403).** 위치정보법 고시 제8조 대응.
  - **스크립트·진단**: 헤더 `x-admin-key: <KEY>` 로만. 예: `Invoke-RestMethod <url> -Headers @{'x-admin-key'=$k}`
  - **브라우저**: `callradar-server.onrender.com/admin/login` 에서 1회 로그인 → 8시간 세션 쿠키(HttpOnly). 주소창에 키가 안 남는다.
  - **키는 어디에도 적지 않는다.** Render 환경변수에만 있다. 필요하면 대표에게 그때그때 받는다(채팅에 남기지 말 것).
  - 모든 관리자 접근은 `admin_access_log` 에 자동 기록된다(고시 제10조, 1년 보존). 실패한 시도도 남는다.
  - 조회 엔드포인트: `/api/admin/testers-data`(활성유저·마지막운행), `/api/debug/logs/:userId`, `/api/admin/work-sessions`(근무세션 요약), `/api/admin/growth`(가입·유지율), `/api/admin/access-log`(접근기록), `/api/admin/bookings-count`. 캐시 우회 `?cb=N`.
- SERVICE 로그에 앱버전 기록됨(`v3.1x2 연결됨 | 앱 2.5.x-onestore`) → 유저 버전 판별.
- 스톨(유령트립) 신호: TRIP_START/BOARDING 후 TRIP_END 없이 배지 물림. 회복: 강제중지/재설치 or R1(새 탑승 감지 시 자동마감). 6시간 상한은 인천공항 장거리 때문 → 시간마감 말고 양성신호로만.

## ★ 상시 자동 점검 (매일 08:00 KST) — 능동성
- 스케줄 `callradar-daily-health`가 admin API로 스톨·이상 유저를 유저 신고 전에 잡아 보고. 방 바뀌어도 계속 돎. 사라졌으면 재생성.
- **능동성 원칙**: 운영·엔지니어링(커밋·버전·보안·모니터링·복구·문서화)은 시키기 전에 먼저. 단 되돌릴 수 없는 조치(스토어 배포·삭제·송금)는 마지막에 대표 확인 1회.

## ★ 릴리스·빌드
- **R8 난독화 켜짐** → 버전마다 mapping.txt 필수 보관. 위치: `C:\CallRadar\_releases\vNN\`(APK+mapping-vNN-x.y.z.txt+README).
- 절차: versionCode 올림 → 커밋 → `.bat`로 `assembleOnestoreRelease` 빌드 → BUILD SUCCESSFUL 확인 → `app/build/outputs/apk/onestore/release/`·`.../mapping/onestoreRelease/mapping.txt`를 `_releases/vNN/`에 복사.
- 앱 소스 `C:\CallRadar\app`, 버전 `app/build.gradle.kts`. JAVA_HOME 매번 `set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`. SDK `C:\AndroidSdk`, adb `C:\AndroidSdk\platform-tools\adb.exe`.
- 릴리스 빌드시간 ~9~12분(R8). 디버그빌드는 카카오맵 "지도 인증 실패"가 정상(키해시 미등록).

## ★ 서버
- **배포 서버: `C:\CallRadar\server`** → GitHub `sollbe82-droid/callradar-server` → Render 자동배포(`callradar-server.onrender.com`). 수정·배포 반드시 여기서. `C:\CallRadarServer`(구폴더)는 폐기.
- Render 스타터(콜드스타트 30~60s). ENFORCE_TOKEN OFF(구버전 로그아웃 방지). ADMIN_KEY Render에 설정됨.
- 계정: device_id 연결(게스트/페어링 = 같은 user_id), 카카오는 kakao_id. **로그인 = 카카오 + 게스트만**(아이디/비번 폐기).

## ★ 현재 상태 (2026-08-14)
- **v62 / 2.6.2 빌드완료·미업로드** (커밋 c87b83f, `_releases/v62/`). 서버 라이브 6a71b10. 상세는 `HANDOVER.md` 0-AA 섹션 먼저 읽기.
  - v60: 근무카드 오늘매출 아래 이동 + 세션거리 수동초기화(548km). v61: 공항 '앞으로 도착 손님' 30분버킷 예측카드. v62: **심플홈(카카오식 무탭) 옵트인 모드**(SimpleHomeScreen/SimpleMenuScreen, home_mode 토글, classic 무손상). 심플 상세=`심플홈_리디자인_스펙.md`.
  - 서버: 콜제보 매출제외 통일, 레이더 GPS 광역분류 cr_region, /api/usage/audit.
  - **v62 심플모드는 실기기 미검증 — 대표 폰 확인 필요.** 콜제보=죽은기능(정리 불요). 우버 0원 파싱 이슈 실재(다음).
- (이하 2026-08-13 v59 이력)

## ★ 이전 상태 (2026-08-13)
- **v59 / 2.5.9 빌드완료·본폰설치·검수제출 예정** (커밋 3741343, `_releases/v59/`). 오늘 v55→v59 누적:
  - v55(ca20209): 요금 총액 오긁기 방지(extractFare 홈 대시보드 마커 제외), 콜취소 후 플로팅 먹통(탭=취소무장).
  - v56(0b9869b·7adc1aa): 자동기록 토글 OFF 실작동(auto_free_open이 토글 덮던 것, auto_record_touched), 티머니 알림도 notif_capture_on 게이트, 근무세션 요약 서버저장(퇴근시 /api/work-session/close). 서버 141d729(work_sessions_log+admin).
  - v57(c0952c8): 토글 3개→1카드, 통행료→지출 자동분리(매출은 미터만, client_uuid 멱등), 자정 날짜귀속(완료시각+day_start_hour, **옵트인**: 근무카드 '영업일 시작시각' 설정해야 활성, 야간기사 회귀방지). 서버 4808a88(PUT /api/trips business_date).
  - v58(52ed664): 홈·기록·월별·달력·공유 매출 통일 = fare+팁+프로모(홈 /api/today와 일치), 보너스(프로모·호출료) 별도표기. 서버 d0a13b7(stats/daily +tip+promo+bonus). **서버 실증: 홈=월별=기록 일치 확인.**
  - v59(3741343): 근무 일시정지 버그(20초 투폰 pull이 로컬 일시정지 덮어써 재개시키던 것 → 로컬변경 후 30초 pull 가드), 이동거리 폭주(속도게이트 60→40m/s·단일 20km→3km). **폰 검증: 일시정지 정지 확인.**
- **다음 versionCode 60+.** 서버 최신 d0a13b7(라이브).

## ★ 열린 작업 (우선순위 순)
- **레이더 지역분류**(서버만, 앱빌드 무관): 전체기사 집계가 동단위 뒤죽박죽 → 광역 매핑. 대표기준: 서울=전체, 인천·경기=시별, 충청/경상/전라=남북 시별, 제주=제주시/서귀포시.
- **홈 근무카드 위로**: 근무블록 300줄+ 상태·다이얼로그 얽힘 → 컴포넌트 분리 리팩터(회귀위험, 별도 집중세션). 출근버튼 HomeScreen L1101±.
- **548km 등 과거 오염 근무기록**: v59가 이후는 막음, 오늘 값은 새 영업일 출근시 리셋. 원할 시 '세션 거리 초기화' 버튼 추가(소).
- 콜제보 매출집계 일관성(홈 /api/today는 콜제보 제외, /api/trips·stats/daily는 미제외 → 콜제보 넣은 날만 소폭 차이).
- 후순위: 학습정확도 채점버그(amount correct≈11/1559, /api/feedback/accuracy·feedback.js), 영수증OCR 실검증+가스영수증 학습(영수증가스·가스비영수증 폴더에 10장), 자동회전(앱무관·USB/삼성쪽).

## ★ 보류 아이디어
- 레이더 지도 "네비처럼 내 위치 따라가기"(대표: 급하지 않음): 현재 GPS 마커+방향화살표+"내 위치로" 버튼, 지도 열 때 서울시청 하드코딩(DriverMapScreen getPosition ~101줄) 대신 내 위치로.

## ★ 과거 이력 (요약 · 상세는 git 로그/HANDOVER)
- 2026-08-02~03: 레이더 수정(효율지표·hotzone GPS필터·경쟁반영), 전면 보안감사(admin ADMIN_KEY 게이트·IDOR·XSS·DB백업워크플로·KST정합), 2차 하드닝(오프라인 지출큐잉·멱등·notif 유령운행제거), A-Z 전수검사(일일마감 LPG·stats 일별집계·크래시수정·readTimeout). 전부 서버 배포+앱 반영 완료.
- v51~v54: R1 유령트립 자동회복, 우버 스톨/0원, R8 난독화, 지도필터, 궤적 실차/boarded_at, 근무 900km 버그(dtSec 게이트 v54), 레이더 내위치 마커, 안내 GPS/지역기준, 학습카드=누적교정N건, 가스 LPG 리터파싱(소수3자리).
