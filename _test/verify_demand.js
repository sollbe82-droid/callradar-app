(async () => {
  const S = 'https://callradar-server.onrender.com';
  const hr = new Date().getHours();
  try {
    const r = await fetch(S + '/api/demand?hour=' + hr);
    const j = await r.json();
    console.log('status', r.status, '| hour', j.hour, '| rows', (j.rows || []).length);
    (j.rows || []).slice(0, 5).forEach(x => console.log('-', x.origin, '| cnt', x.cnt, '| drivers', x.drivers));
  } catch (e) { console.log('ERR', e.message); }
})();
