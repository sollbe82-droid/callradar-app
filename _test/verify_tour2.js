(async()=>{
 const S='https://callradar-server.onrender.com';
 try{
  const a=await (await fetch(S+'/api/events/refresh-status')).json();
  console.log('refresh:',JSON.stringify(a).slice(0,200));
  const b=await (await fetch(S+'/api/events?categories=축제&days=120')).json();
  const arr=Array.isArray(b)?b:[];
  const withArea=arr.filter(x=>x.area).length;
  console.log('축제수:',arr.length,'| 지역채워진수:',withArea);
  // 지역 분포
  const dist={}; arr.forEach(x=>{const k=x.area||'(없음)';dist[k]=(dist[k]||0)+1;});
  console.log('지역분포:',JSON.stringify(dist));
  if(arr[0])console.log('예:',arr[0].title,'/',arr[0].area,'/',String(arr[0].start_at).slice(0,10));
 }catch(e){console.log('ERR',e.message);}
})();
