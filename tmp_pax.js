fetch('https://callradar-server.onrender.com/api/airport/passengers').then(r=>r.json()).then(d=>{
  console.log('passengers rows:', Array.isArray(d)?d.length:JSON.stringify(d).slice(0,200));
  (Array.isArray(d)?d:[]).slice(0,14).forEach(x=>console.log(' hour',x.hour,'t1',x.t1,'t2',x.t2));
}).catch(e=>console.log('ERR',e.message));
