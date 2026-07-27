(async () => {
  const S = 'https://callradar-server.onrender.com';
  try {
    const r = await fetch(S + '/api/events?days=10');
    const t = await r.text();
    let n = 'n/a';
    try { const j = JSON.parse(t); n = Array.isArray(j) ? j.length : 'not-array'; } catch (e) { n = 'HTML/err: ' + t.slice(0, 60); }
    console.log('events status', r.status, 'count', n);
  } catch (e) { console.log('ERR', e.message); }
})();
