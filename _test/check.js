const S='https://callradar-server.onrender.com';const U=205;
(async()=>{
  const r=await fetch(`${S}/api/trips/${U}?limit=8`);const j=await r.json();
  const a=Array.isArray(j)?j:(j.trips||[]);
  console.log('=== /api/trips/205?limit=8 (전체 필터가 쓰는 엔드포인트) 최신순 ===');
  a.forEach(t=>console.log(t.id, String(t.started_at).slice(0,16), t.destination, t.fare, t.source));
  const all=await (await fetch(`${S}/api/trips/${U}?limit=1000`)).json();
  const aa=Array.isArray(all)?all:(all.trips||[]);
  console.log('총 개수:', aa.length);
  // 오늘(2026-07-27) 날짜 기록 수
  const today=aa.filter(t=>String(t.started_at).slice(0,10)>= '2026-07-27');
  console.log('7/27 이후 started_at 기록 수:', today.length);
  const manual=aa.filter(t=>t.source==='manual');
  console.log('source=manual 수:', manual.length);
})();
