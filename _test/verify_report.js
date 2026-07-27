(async () => {
  const S = 'https://callradar-server.onrender.com';
  try {
    const j = await (await fetch(S + '/api/usage/report?days=1')).json();
    console.log('activeUsers', j.activeUsers, '| totalEvents', j.totalEvents);
    console.log('topEvents', JSON.stringify(j.topEvents));
    console.log('topScreens', JSON.stringify(j.topScreens));
  } catch (e) { console.log('ERR', e.message); }
})();
