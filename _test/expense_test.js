const S = 'https://callradar-server.onrender.com';
(async () => {
  const u = 205;
  const p = await fetch(S + '/api/expenses', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: u, category: '잡지출', amount: 12345, expense_type: 'business', memo: '지출테스트', expense_date: '2026-07-26' }) });
  console.log('POST expense status', p.status, await p.text());
  const r = await fetch(S + '/api/expenses/' + u + '?month=2026-07');
  const j = await r.json();
  const found = JSON.stringify(j).includes('지출테스트');
  console.log('월 지출 목록에 뜸?', found, '건수', Array.isArray(j) ? j.length : (j.expenses ? j.expenses.length : 'shape:' + JSON.stringify(j).slice(0, 100)));
})();
