fetch('https://callradar-server.onrender.com/api/events').then(r=>r.json()).then(d=>{
  const rows = Array.isArray(d)?d:(d.events||[]);
  console.log('total:',rows.length);
  rows.forEach(e=>console.log([e.source,e.area,e.start_at&&String(e.start_at).slice(0,10),e.title,e.venue].join(' | ')));
}).catch(e=>console.log('ERR',e.message));
