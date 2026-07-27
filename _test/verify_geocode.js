(async () => {
  const S = 'https://callradar-server.onrender.com';
  // 강남역 좌표
  try {
    const j = await (await fetch(S + '/api/geocode/reverse?x=127.0276&y=37.4979')).json();
    console.log(JSON.stringify(j));
  } catch (e) { console.log('ERR', e.message); }
})();
