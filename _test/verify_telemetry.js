(async () => {
  const S = 'https://callradar-server.onrender.com';
  const post = (b) => fetch(S + '/api/usage', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) }).then(r => r.json());
  try {
    // 익명 사용 이벤트 몇 개 (성공/실패 섞어서)
    await post({ anon_id: 'anonA', event: 'open_screen', screen: 'home', ok: true });
    await post({ anon_id: 'anonA', event: 'open_screen', screen: 'airport', ok: true });
    await post({ anon_id: 'anonB', event: 'save_trip', screen: 'records', ok: true });
    await post({ anon_id: 'anonB', event: 'ocr_report', screen: 'ai', ok: false });
    await post({ anon_id: 'anonC', event: 'ocr_report', screen: 'ai', ok: false });
    const rep = await (await fetch(S + '/api/usage/report?days=7')).json();
    console.log('activeUsers', rep.activeUsers, '| totalEvents', rep.totalEvents);
    console.log('topScreens', JSON.stringify(rep.topScreens));
    console.log('hiddenNeeds(실패많은=숨은니즈)', JSON.stringify(rep.hiddenNeeds));
  } catch (e) { console.log('ERR', e.message); }
})();
