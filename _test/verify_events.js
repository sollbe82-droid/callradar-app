(async()=>{
 const S='https://callradar-server.onrender.com';
 try{
  const b=await (await fetch(S+'/api/events?days=45')).json();
  const arr=Array.isArray(b)?b:[];
  console.log('events days=45 총:',arr.length);
  arr.slice(0,5).forEach(x=>console.log('-',String(x.start_at).slice(0,10),'|',x.category,'|',x.area,'|',x.title));
 }catch(e){console.log('ERR',e.message);}
})();
