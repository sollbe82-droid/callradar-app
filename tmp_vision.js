// vision 엔드포인트 생존 확인 (잘못된 URL로 400/에러 형태만 확인 — 실제 AI 호출 안 함)
fetch('https://callradar-server.onrender.com/api/ai/receipt-vision',{
  method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({url:'https://example.com/x.jpg'})
}).then(async r=>{ console.log('status',r.status,'body',(await r.text()).slice(0,300)); })
 .catch(e=>console.log('ERR',e.message));
