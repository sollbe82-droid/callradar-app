(async () => {
  const S = 'https://callradar-server.onrender.com';
  try {
    const r = await fetch(S + '/api/trips', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        user_id: 1, originName: 'TEST-gangnam', destName: 'TEST-bundang',
        platform: 'callreport', is_report: true, payment_type: 'report', source: 'report',
        raw_ocr: 'RAW OCR sample gangnam bundang 23:40'
      })
    });
    const j = await r.json();
    console.log('status', r.status);
    console.log('raw_ocr in returned row:', JSON.stringify(j.raw_ocr));
    console.log('id', j.id, 'is_report', j.is_report);
  } catch (e) { console.log('ERR', e.message); }
})();
