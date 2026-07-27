(async()=>{
 const S='https://callradar-server.onrender.com';
 try{
  const a=await (await fetch(S+'/api/events/refresh-status')).json();
  console.log('refresh:',JSON.stringify(a).slice(0,300));
  const b=await (await fetch(S+'/api/events?categories=축제&days=90')).json();
  console.log('축제 이벤트수:',Array.isArray(b)?b.length:('ERR '+JSON.stringify(b).slice(0,200)));
  if(Array.isArray(b)&&b[0]) console.log('예시:',b[0].title,'/',b[0].area,'/',String(b[0].start_at).slice(0,10));
 }catch(e){console.log('ERR',e.message);}
})();
