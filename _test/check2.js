const S='https://callradar-server.onrender.com';const U=205;
(async()=>{
  const all=await (await fetch(`${S}/api/trips/${U}?limit=1000`)).json();
  const aa=Array.isArray(all)?all:(all.trips||[]);
  const hit=aa.filter(t=>String(t.fare)==='77777');
  console.log('77777 저장된 것:', hit.map(t=>`id${t.id} ${String(t.started_at).slice(0,19)} ${t.destination}`).join(' | ')||'없음');
  // 07-27 전후 정렬 최신 10
  console.log('--- 최신 10 (started_at desc) ---');
  aa.slice(0,10).forEach(t=>console.log(t.id, String(t.started_at).slice(0,19), t.fare));
  // 오늘 필터가 부르는 것: date=2026-07-27 & dayStart 확인용
  const d0=await (await fetch(`${S}/api/trips/${U}?date=2026-07-27&limit=100&dayStart=0`)).json();
  const d17=await (await fetch(`${S}/api/trips/${U}?date=2026-07-27&limit=100&dayStart=17`)).json();
  const c=x=>Array.isArray(x)?x.length:(x.trips?x.trips.length:0);
  console.log('date=07-27 dayStart=0 →', c(d0), '건 / dayStart=17 →', c(d17), '건');
})();
