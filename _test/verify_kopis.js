(async()=>{
 const S='https://callradar-server.onrender.com';
 try{
  const rf=await (await fetch(S+'/api/events/refresh-kopis')).json();
  console.log('KOPIS refresh:', JSON.stringify(rf).slice(0,220));
  const b=await (await fetch(S+'/api/events?categories=공연&days=60')).json();
  const arr=Array.isArray(b)?b:[];
  console.log('공연 이벤트수:', arr.length);
  arr.slice(0,5).forEach(x=>console.log('-',String(x.start_at).slice(0,10),'|',x.area,'|',x.title));
 }catch(e){console.log('ERR',e.message);}
})();
