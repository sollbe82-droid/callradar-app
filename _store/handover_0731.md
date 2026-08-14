# 콜레이더 인수인계 (2026-07-31)

> 다음 세션이 이 문서만 읽고 바로 이어갈 수 있도록 정리. 이전 세션이 매우 길어져(백화) 이사함.

---

## 0. 제품 개요
- **앱**: 콜레이더(스토어명 "콜레이더 - 택시의신"), 패키지 `com.callradar.app`. Android(Kotlin/Compose). 택시 기사 수입 최적화.
- **서버**: Node.js + Express + PostgreSQL @ Render. `https://callradar-server.onrender.com`
- **배포**: Google Play 비공개 테스트(Alpha) 진행 중. 테스터 ~79명, 14일 요건 채우는 중 → 이후 프로덕션.

## 1. 주요 좌표/값 (중요)
- **로컬 경로**: 앱=`C:\CallRadar`(gradle 루트, git 로컬전용/원격없음), 서버=`C:\CallRadar\server`(git→GitHub), 그 외 `C:\CallRadarServer`.
- **GitHub(유일)**: `https://github.com/sollbe82-droid/callradar-server` (비공개). 앱 소스는 GitHub에 없음(로컬만).
- **Render**: 서비스 `srv-d8l6gkjtqb8s73aru6e0`. 환경변수에 DB/KAKAO/AIRPORT 키 + **ADMIN_KEY 설정됨**(fail-close).
- **Play Console**: 개발자ID `5949869159225298686`, 앱ID `4973750051603798660`. 앱서명 페이지=`.../app/4973750051603798660/keymanagement`.
- **카카오 콘솔**: app `1483101`. 네이티브 키 `fa52bb8b78c8d184988f6ad18f42687e`.
  - 등록된 Android 키해시(둘 다 필요): 업로드키 `OWz233FduQ5xuAdzyBamK2vh//I=`, **구글 앱서명키 `l3KYkj2QXbgVwL3FxBYuF3pLRfM=`**(SHA1 `97:72:98:92:3D:90:5D:B8:15:C0:BD:C5:C4:16:2E:17:7A:4B:45:F3`).
- **보조폰(테스트기기)**: 갤럭시 Z 폴드5 SM-F946N, adb id `R3CW70QB0NY`. 현재 v22 설치(**v23 설치 미확인**).
- **본폰**: 영진의 S26 Ultra (Play로 v22 받음, 지도 정상 확인됨).
- **빌드/배포 방법**:
  - 앱 빌드: `C:\CallRadar`에서 `gradlew.bat assembleRelease`(APK) / `bundleRelease`(AAB, Play용). JAVA=Android Studio jbr. adb=`C:\AndroidSdk\platform-tools`.
  - **주의: assembleRelease는 APK만, bundleRelease는 AAB만 만든다.** Play엔 AAB.
  - 서버 배포: `C:\CallRadar\server`에서 `git add index.js && commit && push origin HEAD` → Render 자동배포.
  - Windows 명령은 파일탐색기 주소창에 `.bat` 경로 입력해 실행(불안정할 때 있음 — 파일 안 생기면 재시도). 샌드박스 bash는 폰/윈도우 git에 접근 못함(마운트만).

## 2. 이번 세션에서 완료한 것 (DONE)
1. **앱 전 기능 테스트**: 홈/플로팅/기록/레이더/더보기 — 크래시 0, 유령데이터 0.
2. **보안 (배포·라이브)**:
   - 서버 IDOR **phase-1**(로그인 토큰 발급 → user_id 검증, 무토큰은 레거시 통과=무중단). 커밋 `e2b9756`.
   - 서버 IDOR **phase-1b**(요금수정 fare·예약상태 소유자 검증). 커밋 `01e26af`.
   - 서버 하드닝(ADMIN_KEY fail-close, 레이트리밋 XFF, 페어링 브루트포스). 커밋 `a272754`.
   - 앱 IDOR: `Auth.kt` + 91곳 openConnection 토큰헤더 주입 + 토큰 생명주기(MainActivity/MoreScreen). 앱 로컬커밋 `f07d1d0`.
   - 검증: 토큰으로 남 데이터 접근 403, 본인 200, 무토큰 200. 실서버 확인 완료.
3. **Render ADMIN_KEY 설정**(자동생성) → 재배포 Live.
4. **v22 출시**: versionCode 22 / versionName 1.9 → Play 비공개테스트 **심사 통과·테스터 전체 출시 완료(7/31)**.
5. **보조폰 스트레스 테스트(v22)**: 몽키 3000이벤트 크래시0, 수동입력 30건 완벽 무결(건수·합계 정확), 삭제 정확. 테스트데이터 정리 완료. (`C:\CallRadar\_test\콜레이더_v22_테스트결과.md`)
6. **지도 인증 실패 해결**: 원인=Play 앱서명으로 본폰은 구글 앱서명키 서명인데 그 키해시가 카카오에 없었음. 위 두 키해시 등록 → **본폰 지도 정상 확인** ✅. (앱 코드 문제 아님)

## 3. 진행 중 / 코드는 됐으나 미검증 (IN PROGRESS)
### 종료시 금액 자동파싱 (endfare) — 코드 완료, **빌드/설치/실기기 테스트 미완**
목표: 기사가 요금 뜬 상태에서 **운행종료** 누르면 → 화면 캡처 → OCR → **금액만 추출** → 기록에 자동입력(반자동). 학습 쌓이면 완전자동. 플랫폼(카카오/우버/티머니) 구분은 출발 스샷+자동학습(미래).

**수정한 파일(디스크 저장됨, 커밋/배포 안 함):**
- `app/.../ScreenCaptureService.kt`: `endfare` 모드 추가 — 전체프레임 OCR→`extractFare()`(요금 키워드 우선, 없으면 최댓값)→`pending_fare` 저장 + 학습원문 로컬누적(`files/fare_learn/log.tsv`). 함수 `parseFareAndStore`, `extractFare` 추가.
- `app/.../FloatingTripService.kt`: 종료 누르면 `capture_purpose=endfare` 세팅 후 `ScreenCapturePermissionActivity.start()`; `createTrip`이 `pending_fare`(90초내) 읽어 `fare`로 POST. 게이트 pref `endfare_on`(기본 true).
- `server/index.js`: `POST /api/trips`가 `fare`,`payment_type` 받아 INSERT (하위호환). **← 이 서버 변경 아직 배포 안 됨!**
- `app/build.gradle.kts`: versionCode **23**(테스트용, 보조폰이 22라 올림), versionName 1.9.

**끊긴 지점**: `C:\CallRadar\_test\build_v23.bat`(assembleRelease + adb install -r) 실행 중 세션 백화 + 샌드박스 다운 → **v23 컴파일/설치 결과 미확인**. `C:\CallRadar\_test\v23.log` 확인 필요.

## 4. 이 방에서 "못한 것" (다음 세션 TODO)
1. **v23 빌드 검증**: `v23.log`에서 BUILD SUCCESSFUL/INSTALL 확인. 실패면 Kotlin 오류 수정 후 재빌드. (endfare 코드 컴파일 확인 최우선)
2. **종료 금액파싱 실기기 테스트**: 실제 카카오T/우버 **요금 화면**에서 종료→금액 정확 추출되는지. OCR 파싱 정확도 튜닝(`extractFare`).
3. **서버 배포**: `server/index.js`의 `/api/trips` fare 변경 커밋·푸시(Render 반영). 안 하면 앱이 fare 보내도 저장 안 됨.
4. **학습데이터 서버화**: 지금은 endfare 원문을 폰 로컬(`fare_learn/log.tsv`)에만 저장. 서버 `/api/fare-log` 만들어 업로드 → A단계 규칙 자동개선 루프(카나리/자동롤백/자기채점).
5. **endfare UX**: MediaProjection 동의창이 종료마다 뜨는 마찰. 더보기에 `endfare_on` 토글 UI 추가(현재 pref만).
6. **보안 phase-2(토큰 강제)**: v22/v23 충분히 퍼진 뒤 서버를 토큰 필수로 전환(전역 미들웨어에서 무토큰 거부). 지금 하면 구버전 접속 끊김.
7. **v22 → 프로덕션 승격**: 14일 비공개테스트 요건 충족 후 신청.
8. **광고물 마무리**: 이미지 6장은 **생성됨**(`C:\CallRadar\_ad\ad_1_hero.png ~ ad_6_multi.png`, 16:9, 신뢰·전문 톤). 아직 사용자 확인/최종화 안 함. **플로팅 GIF, 각 이미지 캡션, 카페 게시글 본문, 업데이트 정리글** 미작성.
9. **플랫폼 자동학습**(출발 스샷으로 카카오/우버 구분), **완전 자동화** — 로드맵.
10. (저우선) 서버 `.env` git 추적 제거 + 시크릿 로테이션(저장소 비공개라 급하지 않음).

## 5. 로드맵/설계 참고
- AI 자동학습·자동기록 상세 설계: `C:\CallRadar\콜레이더_로드맵_v20.md`
  - 핵심: 폰=인식(ML Kit OCR), 서버=학습. "인식→유저교정→서버수집→규칙개선→앱push"(A단계). LLM은 방식B(학습때만)로 저비용. 자동안전장치(카나리/롤백/자기채점/가드레일).
- 광고 강조기능: 자동기록(플로팅), 예상월급·수입최적화, 레이더·공항, 기록·통계·정산(+부가세/LPG), 멀티폰 연동, 명함 QR 예약.

## 6. 커밋/미커밋 상태 (유실 방지)
- 서버 커밋됨: a272754, e2b9756, 01e26af. **미커밋·미배포**: `/api/trips` fare 변경.
- 앱 로컬커밋: f07d1d0(IDOR). **미커밋(디스크 저장됨)**: endfare(ScreenCaptureService/FloatingTripService), build.gradle.kts(versionCode 23).
- 다음 세션에서 v23 검증 후 앱/서버 각각 커밋 권장.

## 7. 바로 이어서 할 첫 작업 (권장 순서)
1. `C:\CallRadar\_test\v23.log` 읽어 빌드 성공 여부 확인 → 성공이면 보조폰 실기기에서 종료 금액파싱 시도, 실패면 오류 수정.
2. 서버 `/api/trips` fare 변경 배포.
3. 광고물(캡션/게시글/GIF) 마무리 + 사용자에게 이미지 6장 보여주기.
