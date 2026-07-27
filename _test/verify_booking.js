(async()=>{
 const S='https://callradar-server.onrender.com';
 try{
  // 1) 웹 예약페이지 렌더 확인
  const page=await (await fetch(S+'/book/205?name=%ED%99%8D%EA%B8%B8%EB%8F%99')).text();
  console.log('예약페이지 HTML?', page.includes('기사님께 예약')?'OK ('+page.length+'byte)':'FAIL');
  // 2) 예약 생성
  const cr=await (await fetch(S+'/api/bookings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({driver_user_id:'205',passenger_name:'테스트승객',passenger_phone:'010-1234-5678',ride_date:'2026-07-28',ride_time:'09:30',origin:'강남역',destination:'인천공항',memo:'검증'})})).json();
  console.log('예약생성:', cr.id?('id'+cr.id+' status='+cr.status):JSON.stringify(cr).slice(0,150));
  // 3) 기사 조회
  const list=await (await fetch(S+'/api/bookings/205')).json();
  console.log('기사 예약목록:', Array.isArray(list)?list.length+'건 최신='+(list[0]?list[0].passenger_name+'/'+list[0].destination:''):JSON.stringify(list).slice(0,150));
 }catch(e){console.log('ERR',e.message);}
})();
