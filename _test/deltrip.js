const B = 'https://callradar-server.onrender.com';
const fs = require('fs');
fetch(B + '/api/trips/1869', { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: 1 }) })
  .then(r => r.text()).then(t => fs.writeFileSync('C:\\CallRadar\\_test\\trips.log', 'DELETED 1869: ' + t))
  .catch(e => fs.writeFileSync('C:\\CallRadar\\_test\\trips.log', 'ERR ' + e));
