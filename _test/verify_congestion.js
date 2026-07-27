(async () => {
  const S = 'https://callradar-server.onrender.com';
  try {
    const j = await (await fetch(S + '/api/airport/congestion?selectdate=0')).json();
    console.log('ok', j.ok, '| notice', j.notice, '| date', j.date);
    console.log('peak', JSON.stringify(j.peak));
    console.log('rows', (j.rows || []).length);
    (j.rows || []).slice(0, 6).forEach(x => console.log('  ', x.time, '| T1', x.t1, '| T2', x.t2, '| 합', x.total));
  } catch (e) { console.log('ERR', e.message); }
})();
