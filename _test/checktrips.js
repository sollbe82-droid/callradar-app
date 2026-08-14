const B = 'https://callradar-server.onrender.com';
const fs = require('fs');
fetch(B + '/api/trips/1').then(r => r.json()).then(all => {
  const list = Array.isArray(all) ? all : (all.trips || []);
  // 2026-07-29 KST 트립 + 금액 관련 필드
  const rows = list.map(t => ({
    id: t.id, fare: t.fare, tip: t.tip, promo: t.promo,
    plat: t.platform, src: t.source, pay: t.payment_type,
    o: t.origin, d: t.destination,
    olat: t.origin_lat, olng: t.origin_lng,
    started: t.started_at, ended: t.ended_at
  }));
  const jul29 = rows.filter(t => (t.started || '').slice(0,10) === '2026-07-29' || (t.ended||'').slice(0,10) === '2026-07-29');
  const fareless = rows.filter(t => !t.fare || t.fare === 0);
  fs.writeFileSync('C:\\CallRadar\\_test\\trips.log',
    'TOTAL '+rows.length+'\n\nJUL29 ('+jul29.length+'):\n'+JSON.stringify(jul29,null,1)+'\n\nFARELESS ('+fareless.length+'):\n'+JSON.stringify(fareless.slice(0,15),null,1));
}).catch(e => fs.writeFileSync('C:\\CallRadar\\_test\\trips.log', 'ERR ' + e));
