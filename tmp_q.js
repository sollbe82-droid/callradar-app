const B='https://callradar-server.onrender.com';
(async()=>{
  try{
    const r=await fetch(B+'/api/quality/report');
    const j=await r.json();
    console.log('=== 데이터 품질 ===');
    console.log('운행:', JSON.stringify(j.trips));
    console.log('세션:', JSON.stringify(j.sessions));
    console.log('이상 사유별:', JSON.stringify(j.reasons));
  }catch(e){ console.log('ERR',e.message); }
})();
