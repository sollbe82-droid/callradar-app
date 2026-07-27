(async () => {
  const S = 'https://callradar-server.onrender.com';
  try {
    const j = await (await fetch(S + '/api/airport/passgr?selectdate=0')).json();
    console.log('header', JSON.stringify(j.header));
    console.log('count', j.count);
    console.log('sample', JSON.stringify(j.sample, null, 1).slice(0, 1500));
  } catch (e) { console.log('ERR', e.message); }
})();
