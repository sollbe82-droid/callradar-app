const { execSync } = require('child_process');
const ADB = '"C:\\AndroidSdk\\platform-tools\\adb.exe"';
const SRV = 'https://callradar-server.onrender.com';
const aid = execSync(ADB + ' shell settings get secure android_id', { encoding: 'utf8' }).trim();
const deviceId = 'guest_' + aid;
(async () => {
  try {
    const g = await (await fetch(SRV + '/api/auth/guest', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ device_id: deviceId, nickname: '기사님' }) })).json();
    const uid = g.user_id;
    console.log('device_id=' + deviceId);
    console.log('user_id=' + uid + '  nick=' + g.nickname);
    const recs = [];
    let total = 0;
    for (let d = 1; d <= 26; d++) {
      const off = (d % 7 === 0);              // 주 1회 휴무
      const income = off ? 0 : (180000 + Math.floor(Math.random() * 220000));
      if (income > 0) { recs.push({ date: `2026-07-${String(d).padStart(2, '0')}`, income, memo: '테스트 매출' }); total += income; }
    }
    const r = await (await fetch(SRV + '/api/import/bulk', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: uid, records: recs }) })).json();
    console.log('import result=' + JSON.stringify(r));
    console.log('생성일수=' + recs.length + '  합계매출=' + total.toLocaleString() + '원');
  } catch (e) { console.log('ERROR ' + e.message); }
})();
