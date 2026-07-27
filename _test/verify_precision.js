(async () => {
  const S = 'https://callradar-server.onrender.com';
  for (const p of ['coarse', 'fine']) {
    try {
      const r = await fetch(S + '/api/demand?precision=' + p);
      const j = await r.json();
      console.log('=== precision', j.precision, '| rows', (j.rows || []).length);
      (j.rows || []).slice(0, 5).forEach(x => console.log('  -', x.origin, '| cnt', x.cnt, '| drivers', x.drivers));
    } catch (e) { console.log('ERR', p, e.message); }
  }
})();
