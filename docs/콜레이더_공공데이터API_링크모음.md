# 콜레이더 — 공공데이터 API 신청 링크 + 추가 추천 (2026-07-27 검증)

발급받은 **인증키(서비스키)를 나에게 전달** → 서버(Render) 환경변수 연결 + 자동수집. (공개데이터 키, 민감도 낮음. Render Environment에 직접 붙여도 됨.)

## 최우선 (전국 이벤트 커버리지)

### 1. 한국관광공사 TourAPI — 전국 축제·행사 (커버리지 최대)
- 신청: https://www.data.go.kr/data/15101578/openapi.do  ("한국관광공사_국문 관광정보 서비스" → 활용신청)
- 참고 포털: https://api.visitkorea.or.kr/
- 쓸 것: 행사정보조회(searchFestival), 지역기반조회(areaBasedList2) → 지역 필터에 최적.

### 2. KOPIS 공연예술통합전산망 — 공연·콘서트 + 인기(박스오피스)
- 인증키 신청: https://www.kopis.or.kr/por/cs/openapi/openApiUseSend.do?menuId=MNU_00074
- API 정보: https://www.kopis.or.kr/por/cs/openapi/openApiInfo.do?menuId=MNU_00074
- 쓸 것: 공연목록, 박스오피스(인기순) → "규모·인기" 선별.

## 우선 (택시 수요·부가가치 직결, 추가 추천)

### 3. 기상청 단기예보 — 날씨→수요·결항
- 신청: https://www.data.go.kr/data/15084084/openapi.do
- (KMA 허브: https://apihub.kma.go.kr/ )
- 이유: 비/눈이면 택시 수요↑, 항공 결항↑ → 추천 보정에 사용.

### 4. 오피넷(한국석유공사) 유가 — LPG·주유 최저가 안내
- 포털: https://www.opinet.co.kr/ (상단 "오픈 API" 메뉴에서 신청)
- 이유: LPG 최저가 주유소 안내 = 특허 발명12(산업서비스 중개) 실시. 지출 절감 기능과 직결.

### 5. 크루즈 입항 / 선박 입출항 — 항구 주변 수요
- 검색·신청: https://www.data.go.kr/ 에서 "선박 입출항" 또는 "크루즈 입항"(해수부·항만공사) → 활용신청
- (인천항 참고: https://www.icpa.or.kr )

## 선택 (교통 소통 — 나중에)

### 6. 국토교통부 교통소통정보 (전국 실시간 소통)
- 신청: https://www.data.go.kr/data/15040463/openapi.do
- ITS 오픈데이터: https://www.its.go.kr/opendata/

### 7. 서울시 교통정보 TOPIS (서울 한정)
- 안내: https://topis.seoul.go.kr/refRoom/openRefRoom_4.do
- 서울 열린데이터광장: https://data.seoul.go.kr/

## 이미 신청 완료 (참고)
- 인천공항: 여객기 운항현황/여객편 주간/승객예고/입국장현황/택시출차
- 한국공항공사: 실시간 항공기 운항/스케줄/공항 혼잡도
- 국토부 TAGO 국내항공운항정보

## 대표님 체크리스트 (순서대로)
1. [ ] TourAPI 활용신청 → 서비스키 복사  (data.go.kr)
2. [ ] KOPIS 오픈API 인증키 발급  (kopis.or.kr)
3. [ ] 기상청 단기예보 활용신청  (data.go.kr)
4. [ ] 오피넷 오픈API 신청  (opinet.co.kr)
5. [ ] (선택) 선박 입출항 / 교통소통정보 활용신청  (data.go.kr)
6. [ ] 발급 키들을 나에게 전달 → 서버 연결

## 집회·티켓판매소
- 공개 API 없음 → 관리자 등록/뉴스 기반. 신청 불필요.
