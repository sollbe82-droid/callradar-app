(async () => {
  const S = 'https://callradar-server.onrender.com';
  try {
    const arr = await (await fetch(S + '/api/events?days=2')).json();
    console.log('count', arr.length);
    if (arr.length) {
      const e = arr[0];
      console.log('keys', Object.keys(e).join(','));
      console.log('title', e.title, '| area', e.area, '| start_at', e.start_at);
    }
  } catch (e) { console.log('ERR', e.message); }
})();
