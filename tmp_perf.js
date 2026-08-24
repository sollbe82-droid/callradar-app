const B='https://callradar-server.onrender.com';
const eps=[
 ['health','/health'],
 ['events','/api/events'],
 ['airport','/api/airport/cached'],
 ['supply','/api/supply-index/1'],
 ['trips','/api/trips/1'],
 ['radar','/api/report-hotspots'],
];
(async()=>{
  for(const [name,p] of eps){
    const t=Date.now();
    try{ const r=await fetch(B+p); const txt=await r.text();
      console.log(name.padEnd(9), String(Date.now()-t).padStart(6)+'ms', 'status',r.status, 'size',txt.length);
    }catch(e){ console.log(name,'ERR',e.message,Date.now()-t+'ms'); }
  }
  // 2회차(워밍업 후)
  console.log('--- 2회차 ---');
  for(const [name,p] of eps){
    const t=Date.now();
    try{ const r=await fetch(B+p); await r.text(); console.log(name.padEnd(9), String(Date.now()-t).padStart(6)+'ms'); }catch(e){ console.log(name,'ERR'); }
  }
})();
