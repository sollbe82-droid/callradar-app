const B = 'https://callradar-server.onrender.com';
const fs = require('fs');
const G = (u) => fetch(B + u).then(r => r.json());
const D = (u, id) => fetch(B + u + '/' + id, { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: 1 }) }).then(r => r.json());
(async () => {
  const k = await G('/api/knowhow/1');
  for (const x of k) if ((x.area || '') === '서초') await D('/api/knowhow', x.id);
  const h = await G('/api/hotspots/1');
  for (const x of h) if ((x.name || '') === '서초') await D('/api/hotspots', x.id);
  fs.writeFileSync('C:\\CallRadar\\_test\\val.log', 'CLEANED knowhow=' + k.length + ' hotspots=' + h.length);
})().catch(e => fs.writeFileSync('C:\\CallRadar\\_test\\val.log', 'ERR ' + e));
