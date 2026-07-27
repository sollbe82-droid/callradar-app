(async()=>{
 const S='https://callradar-server.onrender.com';
 try{
  const p=await (await fetch(S+'/book/205?name=%ED%99%8D%EA%B8%B8%EB%8F%99&tel=010-1234-5678')).text();
  console.log('길이:',p.length);
  console.log('주소검색?', p.includes('daum.Postcode')?'O':'-');
  console.log('자동채움(localStorage)?', p.includes('cr_passenger')?'O':'-');
  console.log('연락처저장(vCard)?', p.includes('BEGIN:VCARD')?'O':'-');
  console.log('기사님전화?', p.includes('기사님 전화')?'O':'-');
 }catch(e){console.log('ERR',e.message);}
})();
