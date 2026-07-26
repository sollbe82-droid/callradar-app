const { execSync } = require('child_process');
const ADB = '"C:\\AndroidSdk\\platform-tools\\adb.exe"';
const SRV = 'https://callradar-server.onrender.com';
const aid = execSync(ADB + ' shell settings get secure android_id', { encoding: 'utf8' }).trim();
const deviceId = 'guest_' + aid;

async function J(url, opt) { const r = await fetch(url, opt); const t = await r.text(); try { return JSON.parse(t); } catch (e) { return t; } }

(async () => {
  const g = await J(SRV + '/api/auth/guest', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ device_id: deviceId, nickname: '기사님' }) });
  const uid = g.user_id;
  console.log('user_id=' + uid);

  // 자정 전후 운행 4건 (야간 기사가 15일 저녁~16일 새벽 근무)
  const trips = [
    { t: '2026-07-15T23:30:00+09:00', fare: 11000, name: 'Aconst_15d_2330' },
    { t: '2026-07-16T01:30:00+09:00', fare: 22000, name: 'Bconst_16d_0130' },
    { t: '2026-07-16T03:30:00+09:00', fare: 33000, name: 'Cconst_16d_0330' },
    { t: '2026-07-16T05:00:00+09:00', fare: 44000, name: 'Dconst_16d_0500' },
  ];
  for (const tr of trips) {
    await J(SRV + '/api/trips/manual', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: uid, originName: '', destName: tr.name, platform: '경계테스트', fare: tr.fare, started_at: tr.t, source: 'manual' }) });
  }
  console.log('생성: A(15일23:30) B(16일01:30) C(16일03:30) D(16일05:00)\n');

  function labelOf(t) { const m = { 'Aconst': 'A(15일23:30)', 'Bconst': 'B(16일01:30)', 'Cconst': 'C(16일03:30)', 'Dconst': 'D(16일05:00)' }; for (const k in m) if ((t.destination || '').startsWith(k)) return m[k]; return null; }

  for (const ds of [0, 4, 17]) {
    console.log('=== dayStart=' + ds + '시 (' + (ds === 0 ? '자정=주간 기본' : ds === 4 ? '새벽4시=일반 야간' : '오후5시=17시 출근 야간') + ') ===');
    for (const date of ['2026-07-15', '2026-07-16']) {
      const arr = await J(SRV + '/api/trips/' + uid + '?date=' + date + '&dayStart=' + ds + '&limit=200');
      const labels = (Array.isArray(arr) ? arr : []).map(labelOf).filter(Boolean).sort();
      console.log('  ' + date + ' 영업일  →  ' + (labels.length ? labels.join(', ') : '(없음)'));
    }
  }
  console.log('\n※ 기대: ds0 → 15일=A / 16일=B,C,D (야간 짤림)');
  console.log('※ 기대: ds4 → 15일=A,B,C / 16일=D (새벽4시까지 전날로)');
  console.log('※ 기대: ds17 → 15일=A,B,C,D / 16일=없음 (17시 출근 야간 한 영업일)');
})();
