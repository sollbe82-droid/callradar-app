fetch('https://callradar-server.onrender.com/api/airport/cached').then(r=>r.json()).then(d=>{
  for (const t of ['t1','t2']) {
    const o=d[t]||{}; const fl=o.flights||[];
    console.log(t,'flights:',fl.length,'immig:',o.immigrationTotal);
    fl.slice(0,8).forEach(f=>console.log(' ',f.flightNo,'sch='+f.scheduledTime,'est='+f.estimatedTime,'k='+f.korean,'f='+f.foreigner));
  }
}).catch(e=>console.log('ERR',e.message));
