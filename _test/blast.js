const SRV = 'https://callradar-server.onrender.com';
async function J(u, o) { const r = await fetch(u, o); const t = await r.text(); try { return { ok: r.ok, s: r.status, body: JSON.parse(t) }; } catch (e) { return { ok: r.ok, s: r.status, body: t }; } }
(async () => {
  const uid = 205;
  // 1) 30일 수입+지출
  const recs = []; let ti = 0, te = 0;
  for (let d = 1; d <= 30; d++) { const off = (d % 7 === 0); const inc = off ? 0 : (180000 + Math.floor(Math.random() * 240000)); const exp = off ? 0 : (15000 + Math.floor(Math.random() * 30000)); if (inc > 0) { recs.push({ date: `2026-07-${String(d).padStart(2, '0')}`, income: inc, expense: exp, memo: '테스트 한달' }); ti += inc; te += exp; } }
  const imp = await J(SRV + '/api/import/bulk', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: uid, records: recs }) });
  console.log('import ' + JSON.stringify(imp.body));
  // 2) 랜덤 개별 운행 (출발/목적/금액 임의) 스트레스 50건
  const places = ['강남역', '인천공항', '판교', '서울역', '홍대입구', '수원', '일산', '잠실', '건대', '미정', '명동', '부평', '송도', '안양', '분당', '청량리', '노원', '사당', '신촌', '동탄'];
  const plats = ['카카오T', '우버', '티머니고', '길빵/예약'];
  const pays = ['card', 'cash', 'auto'];
  const fares = [0, 3500, 8900, 12345, 150000, 999999];
  let ok = 0, fail = 0; const errs = [];
  for (let i = 0; i < 50; i++) {
    const day = 1 + Math.floor(Math.random() * 30);
    const hh = String(Math.floor(Math.random() * 24)).padStart(2, '0');
    const mm = String(Math.floor(Math.random() * 60)).padStart(2, '0');
    const o = places[Math.floor(Math.random() * places.length)];
    const dst = places[Math.floor(Math.random() * places.length)];
    const fare = Math.random() < 0.4 ? fares[Math.floor(Math.random() * fares.length)] : Math.floor(Math.random() * 90000);
    const started = `2026-07-${String(day).padStart(2, '0')}T${hh}:${mm}:00+09:00`;
    const r = await J(SRV + '/api/trips/manual', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: uid, originName: o, destName: dst, platform: plats[Math.floor(Math.random() * plats.length)], fare: fare, payment_type: pays[Math.floor(Math.random() * pays.length)], started_at: started, source: 'manual' }) });
    if (r.ok) ok++; else { fail++; if (errs.length < 6) errs.push('HTTP' + r.s + ' ' + JSON.stringify(r.body).slice(0, 100)); }
  }
  console.log('랜덤운행 성공=' + ok + ' 실패=' + fail);
  if (errs.length) console.log('에러샘플: ' + errs.join(' || '));
  console.log('import수입합=' + ti.toLocaleString() + ' import지출합=' + te.toLocaleString());
})();
