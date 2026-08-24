const B='https://callradar-server.onrender.com';
(async()=>{
  try{
    const r=await fetch(B+'/api/roaddist/report'); const j=await r.json();
    console.log('=== 실주행거리 커버리지 ===');
    console.log(JSON.stringify(j,null,1));
    if(j.real_ratio) console.log(`\n→ 실측 도로/직선 비율: ${j.real_ratio}배 (추정치 1.47과 비교)`);
  }catch(e){ console.log('ERR',e.message); }
})();
