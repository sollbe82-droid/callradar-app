// 밤샘 자동 테스트: 상황별 2달치(6월 클린 + 7월 보강) 데이터를 실제 추가 경로(/api/trips/manual, /api/expenses)로 하나하나 입력하고 집계 검증.
const S = 'https://callradar-server.onrender.com';
const U = 205;
async function J(url, opt) { const r = await fetch(url, opt); const t = await r.text(); try { return { ok: r.ok, s: r.status, b: JSON.parse(t) }; } catch (e) { return { ok: r.ok, s: r.status, b: t }; } }
function utc(dateKST, hh, mm) { // KST date 'YYYY-MM-DD' + h:m -> UTC ISO
  const d = new Date(`${dateKST}T${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:00+09:00`);
  return d.toISOString().slice(0, 23) + 'Z';
}
const PLAT = ['카카오T', '우버', '티머니고', '길빵/예약'];
const PLACES = ['강남역', '인천공항', '판교', '서울역', '홍대', '수원', '일산', '잠실', '건대', '명동', '부평', '송도', '사당', '신촌', '분당', '청량리', '노원'];
const rnd = (a, b) => a + Math.floor(Math.random() * (b - a + 1));
const pick = a => a[Math.floor(Math.random() * a.length)];

(async () => {
  let trips = 0, cash = 0, card = 0, exp = 0, night = 0, added = 0, fails = 0;
  const errs = [];
  // 6월 1~30 (클린) — 상황별
  for (let day = 1; day <= 30; day++) {
    const date = `2026-06-${String(day).padStart(2, '0')}`;
    if (day % 7 === 0) continue; // 주1회 휴무
    const n = rnd(3, 7);
    for (let i = 0; i < n; i++) {
      // 상황: 주간/야간 섞기. 20% 야간(22~26시=다음날 새벽)
      let hh, dateUse = date;
      if (Math.random() < 0.2) { hh = pick([22, 23, 0, 1, 2]); night++; if (hh < 6) { /* 자정 넘김: 날짜 그대로 두고 시각만 새벽 → dayStart 테스트 */ } }
      else hh = rnd(6, 21);
      const mm = rnd(0, 59);
      const pay = pick(['card', 'cash', 'auto']);
      const fare = Math.random() < 0.15 ? pick([0, 3500, 150000, 999999]) : rnd(4000, 45000);
      const tip = Math.random() < 0.1 ? rnd(1000, 5000) : 0;
      const body = { user_id: U, originName: pick(PLACES), destName: pick(PLACES), platform: pick(PLAT), fare, tip, payment_type: pay, source: 'manual', started_at: utc(dateUse, hh, mm) };
      const r = await J(S + '/api/trips/manual', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      if (r.ok) { added++; trips++; if (fare > 0) { if (pay === 'cash') cash += fare; else card += fare; } } else { fails++; if (errs.length < 5) errs.push('trip HTTP' + r.s); }
    }
    // 지출: LPG 매일 + 가끔 식비/세차/주차/잡지출
    const liters = rnd(35, 45), price = pick([1050, 1116, 980]);
    let r = await J(S + '/api/expenses', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: U, category: 'LPG', amount: liters * price, expense_type: 'business', memo: '주유', liters, price_per_liter: price, expense_date: date }) });
    if (r.ok) exp += liters * price; else fails++;
    if (Math.random() < 0.4) { const amt = rnd(6000, 15000); r = await J(S + '/api/expenses', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: U, category: pick(['식비', '세차', '주차']), amount: amt, expense_type: 'personal', memo: '', expense_date: date }) }); if (r.ok) exp += amt; }
  }
  console.log(`생성: 운행 ${added}건(야간 ${night}) 실패 ${fails} | 현금합 ${cash.toLocaleString()} 카드합 ${card.toLocaleString()} 지출합 ${exp.toLocaleString()}`);
  if (errs.length) console.log('에러:', errs.join(', '));

  // === 검증 ===
  console.log('\n=== 6월 집계 검증 ===');
  const daily = (await J(S + `/api/stats/daily/${U}?month=2026-06&dayStart=0`)).b;
  const dates = new Set(); let dup = 0, sumFare = 0, sumCard = 0, sumCash = 0, sumExp = 0, negTrip = 0;
  (Array.isArray(daily) ? daily : []).forEach(r => {
    const k = String(r.date).slice(0, 10); if (dates.has(k)) dup++; dates.add(k);
    sumFare += +r.total_fare || 0; sumCard += +r.card_fare || 0; sumCash += +r.cash_fare || 0; sumExp += +r.expense || 0;
    if ((+r.card_fare || 0) + (+r.cash_fare || 0) !== (+r.total_fare || 0)) negTrip++;
  });
  console.log(`일수 ${dates.size} 중복행 ${dup} | 총매출 ${sumFare.toLocaleString()} 카드 ${sumCard.toLocaleString()} 현금 ${sumCash.toLocaleString()} 지출 ${sumExp.toLocaleString()}`);
  console.log(`카드+현금 != 총매출 인 날: ${negTrip} (0이어야 정상)`);

  // dayStart=17로 야간 자정경계 확인 (6월 일수 변동 여부)
  const d17 = (await J(S + `/api/stats/daily/${U}?month=2026-06&dayStart=17`)).b;
  console.log(`dayStart=17 적용시 6월 행수 ${Array.isArray(d17) ? d17.length : 'ERR'} (야간 재분류 반영)`);

  // 지출 요약
  const es = (await J(S + `/api/expenses/summary/${U}?month=2026-06`)).b;
  console.log('지출요약:', JSON.stringify(es));
  console.log('\n검증 끝. 중복행·불일치 0이면 정상.');
})();
