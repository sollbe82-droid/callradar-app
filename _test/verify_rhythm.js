(async () => {
  const S = 'https://callradar-server.onrender.com';
  try {
    const r = await fetch(S + '/api/rhythm/1');
    const j = await r.json();
    console.log('status', r.status, '| total', j.total_trips);
    console.log('top dow', JSON.stringify(j.by_dow && j.by_dow[0]));
    console.log('top hour', JSON.stringify(j.by_hour && j.by_hour[0]));
  } catch (e) { console.log('ERR', e.message); }
})();
