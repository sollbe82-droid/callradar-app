const B = 'https://callradar-server.onrender.com';
const fs = require('fs');
const LOG = 'C:\\CallRadar\\_test\\val.log';
const P = (u, b) => fetch(B + u, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) }).then(r => r.json());
const G = (u) => fetch(B + u).then(r => r.json());
(async () => {
  await P('/api/knowhow', { user_id: 1, area: '서초', time_band: '', pattern: '', note: '검증 테스트' });
  await P('/api/hotspots', { user_id: 1, name: '서초', lat: 0, lng: 0, time_band: '', note: '검증 테스트' });
  const k = await G('/api/knowhow/1');
  const h = await G('/api/hotspots/1');
  const out = 'KNOWHOW ' + JSON.stringify(k.map(x => ({ area: x.area, confirmed: x.confirmed }))) +
    '\nHOTSPOTS ' + JSON.stringify(h.map(x => ({ name: x.name, confirmed: x.confirmed })));
  fs.writeFileSync(LOG, out);
})().catch(e => fs.writeFileSync(LOG, 'ERR ' + e));
