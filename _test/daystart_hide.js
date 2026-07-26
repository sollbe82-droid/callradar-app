const S = 'https://callradar-server.onrender.com';
(async () => {
  const u = 205;
  const started = '2026-07-26T03:00:00.000Z'; // 07-26 12:00 KST
  const p = await fetch(S + '/api/trips/manual', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: u, originName: '', destName: '수동테스트정오', platform: '길빵/예약', fare: 15000, payment_type: 'card', source: 'manual', started_at: started }) });
  console.log('POST status', p.status);
  const g = async (ds) => { const r = await fetch(S + '/api/trips/' + u + '?date=2026-07-26&dayStart=' + ds + '&limit=200'); const j = await r.json(); return Array.isArray(j) ? j.filter(t => JSON.stringify(t).includes('수동테스트정오')).length : ('ERR:' + JSON.stringify(j).slice(0, 80)); };
  console.log('dayStart=0  오늘(07-26)에 포함?', await g(0));
  console.log('dayStart=17 오늘(07-26)에 포함?', await g(17));
})();
