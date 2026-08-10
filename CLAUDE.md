# 콜레이더(CallRadar) 프로젝트 정관 — Claude 필독

> 이 파일은 매 세션 자동 로드된다. 여기 적힌 규칙·사실을 어기지 말 것.

## ★★ 인수인계 / 현재 최우선 (2026-08-03, 새 방 이사)
- **전체 인수인계 문서: `C:\CallRadar\HANDOVER.md` 먼저 읽을 것** (지금까지 한 일·열린작업·접속정보 총정리).
- **해결됨(2026-08-03 #2)**: 개인택시 홈 버그(4-A), 레이더 카톡시드 블렌딩(4-D, 라이브), 운행궤적 라이트다크·미작동(필터50→150m+빈화면 안내), 더보기·기록 스크롤 위치 보존, 회사프로필 자동시드 제거.
- 유저버그 3건 해결(v35): 기록탭 수정/삭제 UI튐(조용한 갱신), 지출 수정기능(서버 PUT `/api/expenses/:id`), 근무세션 km 정지(순간속도 필터).
- v36 추가: 레이더 지도에 오늘 궤적 폴리라인(릴리스만 렌더), 홈 금액중복 정리(기사몫→급여계산기 바로가기, 실기기 검증), 플로팅 100m필터 개선(2분+면 기록), 플로팅 취소창 3s→5s 명확화.
- 그 외 열린: ENFORCE_TOKEN 켜기, 실기기 심사, 궤적 "1번"(플로팅 시 기록) 적용 여부, 사납 남은금액 카운터(#1 보류).
- v37 추가: 레이더 지도 🧭 궤적 온/오프 토글, 실차/공차 거리 정확도 수정(400m컷→순간속도), 과거날짜 궤적 조회(TrackActivity ◀▶, 보관 7→31일).
- **현재 앱 v37/2.3.7 (플레이 AAB+원스토어 APK+디버그 빌드 완료, 대표 업로드 예정·프로덕션 통과), 서버 라이브(커밋 c05413e). 다음 versionCode는 38+.**

## ★ 도메인 사실 (반복 실수 금지)
- **한국 택시 플랫폼(카카오T·우버·타다 등)은 콜/운행 완료 "알림"이 없다.**
  → 따라서 **"알림 읽기(Notification Listener)로 운행 자동기록" 방식은 절대 제안·구현 금지.** (계속 실수한 항목)
- **접근성(Accessibility) 자동기록은 Play 심사 거부·삭제 위험이 커서 주력으로 쓰지 않는다.** 안전한 대안은 기사가 직접 누르는 방식.
- 실제 운행·매출 기록 수단은 (1) 운행 기록 버튼(FloatingTripService, 탑승/완료 직접 누름), (2) 기록 탭 "추가"(QuickEntry 수동 입력), (3) 플랫폼 **정산 데이터 가져오기(가장 정확)**. 미터기는 재미·GPS 추정용이라 기록에 저장하지 않는다.
- 미터기 요금 심야할증(서울 현행 2023.2~): 22~23시 20%, 23~02시 40%, 02~04시 20%.

## ★ 스토어 구분 (원스토어 vs 구글 플레이) — 반드시 지킬 것
- **빌드는 flavor 2개**: `play`(구글 플레이) / `onestore`(원스토어). `app/build.gradle.kts`에서 분기.
- **onestore = 접근성 ON = "v9 완전자동".** 접근성(NaviIntentReceiver)으로 카카오T·우버 콜 시작을 감지 → **플랫폼 자동판별 + 운행 GPS 자동기록**이 된다. 자동화는 원스토어 전용.
- **play = 접근성 OFF.** 구글 정책상 접근성 자동기록은 **심사 거부·삭제 위험**이 커서 일부러 뺐다. 플레이는 **기사가 플로팅 버튼으로 직접 시작/종료**, 종료 팝업에서 플랫폼 직접 선택(수동).
- 정리: **플랫폼 자동판별·GPS 자동기록 = 원스토어에서만.** 기술적으로 불가능한 게 아니라 정책상 플레이에서 뺀 것 → "플레이에도 자동기록 넣자"는 제안 금지.
- **카드승인 알림 금액 자동입력(CallCaptureService, NotificationListener)** 은 별개 기능으로 양쪽(play·onestore) 다 노출(대표 결정, v43). 단 Play는 알림접근 심사 민감(리스크 감수). 카드 알림엔 **금액만** 있고 플랫폼 정보는 없다 → 플랫폼은 시작판별(원스토어) 또는 팝업 수동선택으로 정한다.

## ★ 작업 원칙
- **꼼꼼하고 세심하게.** 추측으로 "다 됐다" 선언 금지.
- **"완료"는 실제 컴파일/빌드 통과를 확인한 뒤에만 보고.** 코드만 넣고 됐다고 하지 말 것.
- 같은 지적을 반복하지 말 것. 이 정관을 먼저 확인하고 답할 것.
- **★ 아이디어·개선점을 들으면 바로 빌드/구현부터 하지 말 것.** 먼저 (1) 생각하고, (2) 코드로 검증하고, (3) 가상으로 동작을 시뮬레이션(엣지케이스 포함)한 뒤, (4) **역제안**까지 붙여서 답을 먼저 준다. 대표가 시킨 그대로만 하지 말고, 더 나은 방법·리스크·트레이드오프를 능동적으로 제시할 것. 합의된 뒤에 구현·빌드한다. (여러 번 지적된 항목)
- **★★ 빌드/업로드 직전 체크리스트(빼먹음 방지).** 대표가 여러 건을 말하면 우선순위가 서로 달라 일부가 누락되기 쉽다. 그러니 **빌드(또는 스토어 업로드)를 시작하기 전에, 이번에 요청·논의된 항목을 목록으로 정리해 대표에게 "이번 빌드에 이거이거 넣습니다 / 빠진 것 없나요?"라고 먼저 물어 확정**한다. 확인 후 빠진 게 없으면 그때 빌드·업로드한다. 확인 없이 일부만 빌드해서 나머지를 잊는 일 없게 할 것. (대표 요청 규칙)

## ★ Claude 반복 금지 — 2026-08-10 자동기록 스톨 사태 교훈 (꼭 지킬 것)
- **① 시간대: 서버 로그·DB timestamp는 전부 UTC.** 사람에게 말할 땐 반드시 **KST(+9)로 환산**해서 말한다. (오늘 UTC를 현지시각으로 오독해 "새벽 3시" 식으로 타임라인을 통째로 오진함.)
- **② 유저 보고를 먼저 믿어라.** "서버엔 기록이 있으니 화면 표시 문제일 것" 식으로 대표·유저의 '진짜 안 된다'를 깎아내리지 말 것. **서버 정상 ≠ 클라 정상.** 배지·`activeTripId`·플로팅 버튼 상태까지 확인한다.
- **③ 자동기록 버그는 '상태머신'부터.** 화면파싱/주소긁기 같은 곁가지 말고 **트립 상태 생명주기**(`lastTripId`/`activeTripId`, `finalizeCurrentTrip`/merge/force-end 경로)부터 추적한다. (오늘 주소긁기부터 의심해 시간 버림. 진짜 원인은 유령트립 미마감 + 죽어있던 강제마감 코드였음 → R1로 수정.)
- **④ ★ 버전 올릴 때마다 git 커밋(필수).** `versionCode` 올리면 즉시 커밋(태그 권장). 안 하면 회귀 발생 시 **이전↔현재 diff를 못 떠 진단이 막힌다.** (v26~v50 미커밋으로 v48↔v49 비교 불가 → 오늘 크게 고생.)
- **⑤ 빌드는 Claude가 직접 한다 — "윈도우에서 하세요"로 떠넘기지 말 것.** 리눅스 작업칸은 SDK 없음 + `dl.google.com`/`services.gradle.org`/`repo1.maven.org` 차단이라 컴파일만 못 한다. 대신 **`.bat` 스크립트를 만들어**(`set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` + `cd /d C:\CallRadar` + `call gradlew.bat :app:assembleOnestoreRelease > build_r1.log 2>&1`) **파일탐색기(claude-in-chrome 아님, computer-use) 주소창에 `C:\CallRadar\_build_r1.bat` 입력→실행 → `C:\CallRadar\build_r1.log`를 Read로 읽어 `BUILD SUCCESSFUL` 확인**하면 Claude가 직접 빌드·검증한다.
- **⑥ 결론은 검증 후에만.** 로그·코드로 확인하기 전 "원인 확정/다 됐다" 금지(기존 ★작업 원칙 재확인).

### 진단 도구 (빠른 참조)
- **관리자 진단 URL**(ADMIN_KEY는 HANDOVER.md 참조): `callradar-server.onrender.com/api/admin/testers-data?key=<KEY>` (활성유저·마지막운행·오늘건수 + 최근 120 로그), `/api/debug/logs/:userId?key=<KEY>` (유저별 최근 100). 샌드박스에선 DB 직결 안 되니 **claude-in-chrome으로 URL 열어 JSON을 읽는다.**
- **v50부터 SERVICE 로그에 앱버전 기록**(`v3.1x2 연결됨 | 앱 2.5.0-onestore`) → 유저가 몇 버전인지 로그로 바로 판별 가능.
- **스톨(유령트립) 신호**: `TRIP_START`/`BOARDING` 후 `TRIP_END` 없이 배지 "운행중" 물림 + 업뎃 순간 `SERVICE` 재연결. **회복**: 앱 강제중지/재설치, 또는 R1(새 손님 탑승 감지 시 유령 자동마감). **6시간 스톨 타임아웃은 인천공항 등 장거리(예: 59,000원 운행) 때문 → 시간으로 마감하지 말고 '새 탑승' 같은 양성 신호로만 회복.**

### 상시 자동 점검 (매일 08:00 KST) — 능동성 원칙
- 스케줄 작업 **`callradar-daily-health`** 가 매일 08시(KST) 자동 실행 → admin API로 로그를 훑어 스톨·이상 유저를 **유저가 신고하기 전에** 잡아 대표에게 보고한다. (파일: `C:\Users\류이\Claude\Scheduled\callradar-daily-health\SKILL.md`)
- **방이 바뀌어도 이 점검은 계속 돈다**(앱 닫혀 있으면 다음 실행 시 돎). 사라졌으면 다시 만들 것.
- **Claude 능동성 원칙(대표 요청, 2026-08-10):** 콜레이더의 운영·엔지니어링 프로세스(커밋·버전관리·보안·모니터링·자동복구·문서화)는 Claude가 **시키기 전에 먼저** 세운다. 단 되돌릴 수 없는 조치(스토어 배포·삭제·송금)는 마지막에 대표 확인 1회.

## ★ 서버 (배포)
- **실제 배포 서버: `C:\CallRadar\server`** → GitHub `github.com/sollbe82-droid/callradar-server` → Render 자동배포(`callradar-server.onrender.com`). 수정·배포는 반드시 여기서.
- `C:\CallRadarServer`(구폴더)는 **폐기됨** — 보지 말 것.
- 서버는 device_id로 계정 연결(게스트/아이디/페어링 = 같은 user_id). 카카오는 kakao_id 단독(아이디 계정과 별개).

## ★ 빌드 방법 (안드로이드 앱)
- 앱 소스: `C:\CallRadar\app`, 버전: `app/build.gradle.kts`의 versionCode/versionName.
- JAVA_HOME 셸에 없음 → 매번 `set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`.
- SDK: `C:\AndroidSdk`. adb: `C:\AndroidSdk\platform-tools\adb.exe`.
- 릴리스 AAB: `gradlew.bat :app:bundlePlayRelease` → 산출물 `_store\callradar-vNN-play-release.aab`.
- 디버그 빌드는 서명키가 달라 **카카오맵 "지도 인증 실패"가 정상**(디버그 키 해시 미등록). 릴리스는 정상.

## ★ 로그인 (현재 방침)
- **카카오 + 게스트("로그인 없이 둘러보기")만** 사용. 아이디/비밀번호 로그인·회원가입은 **폐기**(카카오와 계정이 갈리는 문제 + 복잡성).

## 현재 최신 버전 / 스토어 업로드
- **★ 릴리스 AAB 경로(고정): `C:\CallRadar\app\build\outputs\bundle\playRelease\app-play-release.aab`** — gradle 기본 출력. **`_store`로 복사하지 말 것**(대표가 이 gradle 경로를 직접 씀). `gradlew.bat :app:bundlePlayRelease` 하면 여기에 생김.
- **현재: versionCode 34 / 2.3.4.** 릴리스 서명 빌드 성공(BUILD SUCCESSFUL), 대표 Play 업로드 진행 중. 내용: 4-A 개인택시 홈, 운행궤적 라이트다크+미작동(필터50→150m·빈화면 안내), 더보기·기록 스크롤 보존, 회사프로필 자동시드 제거.
- versionCode는 Play가 "이전 업로드보다 큰 값"만 받음 → 다음 업로드는 35 이상으로.
- 내용(2.3.3): 2차 하드닝 앱분(오프라인 지출 큐잉+멱등키, 딥링크 whoami 검증, cleartext off, LocalTripDatabase onUpgrade 보존, Cloudinary 토큰분리) + 회귀수정(오프라인 지출 날짜필드 date→expense_date, 지출 client_uuid 멱등, 카카오 whoami 콜드스타트 2회재시도, 오프라인 저장 토스트).
- (이전 v32/2.3.2 = 로그인 재설계·미터기 기록저장 제거·심야할증 정확도·레이더 hotzone GPS필터. 실제 업로드 안 됨 — v33이 이를 포함·대체.)

## ★ 전면 보안·데이터 감사 후 수정 — 서버 배포 완료 (2026-08-03, GitHub 8126f60)
- **인증/IDOR**: admin 4종(`/api/admin/*`, `/admin/:company`)+radar `overview` → ADMIN_KEY 게이트(헤더 `x-admin-key` 또는 `?key=`). 대시보드 URL: `.../admin/회사명?key=<ADMIN_KEY>`. **ADMIN_KEY는 Render에 이미 설정됨**(`90d966...e8b8`). 라이브 검증: 무키 403, 키 200.
- `/api/pair/create`=로그인 주폰만(무토큰 401). `/api/pair/merge`=넘겨줄 계정 소유자만+트랜잭션. `/api/users/merge`=관리자 전용+트랜잭션(앱 미사용). `/api/auth/token` 계정탈취 차단(미활성 계정 1회 링크만).
- **ENFORCE_TOKEN은 계속 OFF**(구버전 앱 로그아웃 방지). 전역 무토큰 read-IDOR 완전차단은 이 env를 `true`로 켤 때. 켜기 전 무토큰 요청 비율 확인 권장.
- **DB 백업**: Render 무료 아님(Starter)이지만 자동백업 미보장 → `server/.github/workflows/db-backup.yml`(매일 KST03시 pg_dump→아티팩트) + `server/backup/`(ps1/sh/README). **작동시키려면 GitHub 저장소에 `DATABASE_URL` 시크릿 등록 필요**(대표가 직접, 크리덴셜이라 Claude가 입력 불가).
- **스키마 소스화**: 소스에 없던 `users/trips/receipts` CREATE TABLE을 index.js 상단에 추가(빈 DB 재해복구용, 기존 DB 무영향).
- **데이터 안정성**: `pool.on('error')`+`unhandledRejection/uncaughtException` 핸들러(유휴연결로 프로세스 죽는 것 방지). `import/bulk` 지출 중복삽입 제거(멱등키). 일일마감(정산+LPG) 트랜잭션화.
- **KST 정합**: 주간통계 일별집계 KST 누락 수정, 연속일 야간 자정 오차 수정, radar/deadzone `ended_at`(timestamptz) 이중변환 시간오차 수정.
- 미완(후속): 클라 오프라인 지출 큐잉(앱 재빌드 필요), notif-capture 요금 오귀속 검토, admin 대시보드 photo_url XSS 이스케이프.

## ★ 2차 하드닝 — 서버 배포완료 + 앱 컴파일검증 (2026-08-03, GitHub 88c8e34)
- **서버(배포·라이브검증)**: 대시보드 XSS 이스케이프(company명+기사입력+photo_url, `</script>` breakout까지 차단), `/api/trips` limit검증+offset 페이지네이션, 자동운행 client_uuid 멱등(포인트 이중가산 방지), notif-capture **유령운행 생성 제거+매칭 6h→20분**(오귀속/정관위반 차단), 보안헤더(nosniff/frame/referrer), `/api/auth/logout`(토큰폐기)·`/api/auth/whoami`(딥링크검증용), PUT users 매요청 ALTER→startup 이동.
- **앱(컴파일 BUILD SUCCESSFUL, 실기기 검증은 미완)**: 오프라인 지출 큐잉(LocalTripDatabase에 local_expenses + savePendingExpense/syncPendingExpenses, RecordsScreen "전송먼저→실패시 로컬큐", MainActivity 실행 시 재전송), LocalTripDatabase.onUpgrade **DROP제거**(미전송 운행 유실 방지, v3→4 additive), 지출삭제 타임아웃, Cloudinary에 세션토큰 미전송, cleartext off, 카카오 딥링크 whoami 검증(무검증 계정주입 차단).
- **미완/후속**: 앱 **실기기 런타임 테스트 + 스토어 업로드**(대표 결정). 토큰 EncryptedSharedPreferences/백업제외(계정분리 위험 있어 토큰 전용 prefs 분리 리팩터 필요). 카카오 OAuth state/CSRF·토큰 URL노출(앱+서버 동시변경 필요).

## ★ A-Z 전수검사 후 수정 — 서버 배포완료 + 앱 AAB 재빌드(v33) (2026-08-03, GitHub b142e53)
- **서버(배포·라이브검증)**: 일일마감 가스비 0 재정정 시 옛 LPG지출 잔존 버그(무조건 DELETE 후 >0만 INSERT), stats/insights 주중·주말 평균을 운행별→**일별 집계**로 정정(월급/평균 표시 정확), `/api/events/refresh-status·refresh-kopis` 무인증 DB변조 → admin 게이트(라이브 403 확인), 빈DB 재해복구용 `users.points/total_trips` + `guilds/guild_members/call_logs` CREATE 보강, 자동운행 payment_type 기본 cash→auto(카드수입 오분류 방지).
- **앱(BUILD SUCCESSFUL, AAB 재빌드)**: WorkSessionService 위치권한 없을 때 location타입 FGS **크래시** 수정(권한없으면 타입없이 시작→즉시 종료), 미터기 알림ID 3101→3105 충돌해소, Records/Stats/Ranking 읽기 **readTimeout 추가**(콜드스타트 무한 스피너 해결), 수동 운행저장 **성공확인→실패 시 다이얼로그 유지+토스트**(운행 유실 방지).
- **미완/후속(중·저위험)**: 랭킹 주간 UTC→KST, 세무 연도경계 KST·개인택시 부가세 면세, receipts/bookings 멱등, 갤러리 이미지 디코드 OOM(영수증/명세서 스캔), 영수증스캔 지출 오프라인큐+멱등, 나머지 readTimeout/responseCode 다수(HomeScreen stats/daily 콜드스타트 시 법인월급 과대표시 포함), MoreScreen AI·RadarScreen 타임아웃+인증헤더, MediaProjection FGS 제거(Play정책), QuickEntry tip/promo 오프라인 유실.

## ★ 레이더(radar2) 수정 — 서버 배포 완료 (2026-08-02, GitHub 95ede7f)
- **①효율 지표 모순 수정**: `/api/radar/efficiency` — 실차시간(rideMin)=0이면 실차율·순원분 null(측정불가). 매출÷공차로 순원분 양수 되던 버그 제거. (검증: 308계정 occupancyRate/wonPerMin=null)
- **②hotzone GPS 반경 필터**: `/api/radar/hotzone?lat=&lng=` (반경 8km). 위치 주면 그 근처 콜만 집계 → 전국(인천/역삼) 뒤섞임 방지. 클라(RadarScreen)가 GPS 넘김. (검증: 강남좌표 조회 시 서울 강남권만, 인천 송도·석남 제외됨)
- **③경쟁 반영**: hazard=콜수/(공차대기+30) — 이미 경쟁(대기) 반영. 라벨만 "밀도"→"경쟁 반영" 정정.
- 서버는 GitHub push→Render 자동배포. 클라 변경은 v32 AAB에 포함(재빌드됨).
