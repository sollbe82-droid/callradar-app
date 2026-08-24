Promise.all([
  fetch('https://callradar-server.onrender.com/api/airport/cached').then(r=>r.json()).catch(e=>({err:e.message})),
  fetch('https://callradar-server.onrender.com/api/events').then(r=>r.json()).catch(e=>({err:e.message}))
]).then(([a,ev])=>{
  const t1=a.t1||{};
  console.log('in30Pax:',t1.in30Pax,'in60Pax:',t1.in60Pax,'(undefined=구버전 아직)');
  const rows=Array.isArray(ev)?ev:(ev.events||[]);
  const big=rows.filter(e=>/빅뱅|BIGBANG/i.test(e.title||''));
  console.log('events:',rows.length,'| 빅뱅:',big.length?JSON.stringify(big[0]):'없음');
  const ai=rows.filter(e=>e.source==='ai');
  console.log('AI행사 건수:',ai.length, ai.map(e=>e.title).join(' / '));
});
