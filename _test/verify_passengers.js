(async () => {
  const S = 'https://callradar-server.onrender.com';
  try {
    const j = await (await fetch(S + '/api/airport/passengers')).json();
    console.log('isArray', Array.isArray(j), '| len', j.length);
    (j.slice ? j.slice(0, 8) : []).forEach(x => console.log('  ', String(x.hour).padStart(2, '0') + '시', '| T1', x.t1, '| T2', x.t2));
  } catch (e) { console.log('ERR', e.message); }
})();
