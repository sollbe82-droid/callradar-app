(async () => {
  const S = 'https://callradar-server.onrender.com';
  try {
    const r = await fetch(S + '/api/trips/manual', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        user_id: 1, originName: 'TEST-gangnam', destName: 'TEST-bundang',
        platform: 'callreport', is_report: true, payment_type: 'report', source: 'report',
        raw_ocr: 'RAW OCR sample gangnam to bundang 23:40'
      })
    });
    const j = await r.json();
    console.log('status', r.status);
    console.log('id', j.id, '| is_report', j.is_report, '| raw_ocr', JSON.stringify(j.raw_ocr));
    // hotspot endpoint
    const h = await fetch(S + '/api/report-hotspots');
    const hj = await h.json();
    console.log('hotspots status', h.status, '| rows', Array.isArray(hj) ? hj.length : hj);
  } catch (e) { console.log('ERR', e.message); }
})();
