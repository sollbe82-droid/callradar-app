(async()=>{
 const S='https://callradar-server.onrender.com';
 try{
  const a=await (await fetch(S+'/api/events/refresh-status')).json();
  console.log('refresh:',JSON.stringify(a).slice(0,200));
  const b=await (await fetch(S+'/api/events?categories=축제&days=120')).json();
  const arr=Array.isArray(b)?b:[];
  const withArea=arr.filter(x=>x.area).length;
  console.log('축제수:',arr.length,'| 지역채워진수:',withArea);
  arr.slice(0,4).forEach(x=>console.log('- ',x.title,'| area=',x.area,'| venue=',(x.venue||'').slice(0,20)));
 }catch(e){console.log('ERR',e.message);}
})();
