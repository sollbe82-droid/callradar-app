// 완성판: 앱 UI를 실제 탭/입력해 날짜·플랫폼·결제·시간·출발/목적지·금액 전부 랜덤 입력→저장 반복. 매번 서버 검증.
// 사용: node autobatch2.js [N] [fast]
const {execSync}=require('child_process');
const ADB='C:\\AndroidSdk\\platform-tools\\adb.exe';
const fs=require('fs');
const S='https://callradar-server.onrender.com';
const U=205;
const N=parseInt(process.argv[2]||'2',10);
const FAST=process.argv[3]==='fast';
const K=FAST?0.45:1; // 속도 배수
function sh(c){try{return execSync(c,{encoding:'utf8',maxBuffer:1<<26});}catch(e){return e.stdout||'';}}
function sleep(ms){execSync(`${ADB} shell sleep ${(ms*K)/1000}`);}
function dump(){sh(`${ADB} shell uiautomator dump /sdcard/ui.xml`);sh(`${ADB} pull /sdcard/ui.xml C:\\CallRadar\\_test\\ui.xml`);return nodes(fs.readFileSync('C:/CallRadar/_test/ui.xml','utf8'));}
function nodes(x){const out=[];const re=/<node ([^>]*?)\/?>/g;let m;while((m=re.exec(x))){const a=m[1];const g=k=>{const mm=a.match(new RegExp(k+'="([^"]*)"'));return mm?mm[1]:'';};const t=g('text'),d=g('content-desc'),b=g('bounds');const bb=b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);let cx=0,cy=0;if(bb){cx=Math.round((+bb[1]+ +bb[3])/2);cy=Math.round((+bb[2]+ +bb[4])/2);}out.push({t,d,cx,cy});}return out;}
function find(ns,txt){return ns.find(n=>n.t===txt)||ns.find(n=>n.t&&n.t.includes(txt));}
function findRe(ns,re){return ns.find(n=>n.t&&re.test(n.t));}
function tap(x,y){sh(`${ADB} shell input tap ${x} ${y}`);}
function tapText(ns,txt){const n=find(ns,txt);if(n){tap(n.cx,n.cy);return true;}return false;}
function back(){sh(`${ADB} shell input keyevent 4`);}
function swipe(x1,y1,x2,y2){sh(`${ADB} shell input swipe ${x1} ${y1} ${x2} ${y2} 250`);}
function typeInto(label,val){let ns=dump();let n=find(ns,label);
  if(!n){swipe(900,1300,900,800);sleep(500);ns=dump();n=find(ns,label);} // 아래 필드면 스크롤해서 띄움
  if(!n){return false;}
  tap(n.cx,n.cy);sleep(500);sh(`${ADB} shell input text ${val}`);sleep(350);back();sleep(450);return true;}
const rnd=(a,b)=>a+Math.floor(Math.random()*(b-a+1));
const pick=a=>a[Math.floor(Math.random()*a.length)];
const PLAT=['카카오T','우버','티머니고','길빵/예약'];
const PAY=['카드','현금','자동결제'];
const PLACES=['GangnamStn','ICN-T1','ICN-T2','Pangyo','SeoulStn','Hongdae','Suwon','Ilsan','Jamsil','Myeongdong','Sindorim','Songdo','Sadang','Bundang','Nowon'];
async function tripCount(){try{const r=await fetch(`${S}/api/trips/${U}?limit=1000`);const j=await r.json();return Array.isArray(j)?j.length:(j.trips?j.trips.length:0);}catch(e){return -1;}}

function pickDate(){ // 날짜 버튼 열고 이번달/지난달 랜덤 일자 선택
  // 다이얼로그 최상단으로 스크롤(아래로 스와이프) 후 날짜 버튼 탭
  swipe(900,700,900,1400);sleep(400);
  let ns=dump();let db=findRe(ns,/\(오늘\)|\d{4}-\d{2}-\d{2}/);
  if(!db){return false;}
  tap(db.cx,db.cy);sleep(1200);ns=dump();
  if(!find(ns,'날짜 선택')){return false;}
  // 40% 확률로 지난달로
  if(Math.random()<0.4){const prev=ns.find(n=>n.d==='이전 달로 변경');if(prev){tap(prev.cx,prev.cy);sleep(800);ns=dump();}}
  const day=rnd(1,28);
  const dn=findRe(ns,new RegExp(`[^0-9]${day}일`));
  if(dn){tap(dn.cx,dn.cy);sleep(400);}else{/* 못찾으면 오늘 유지 */}
  ns=dump();tapText(ns,'선택');sleep(700);return true;
}

(async()=>{
  const before=await tripCount();
  console.log(`시작 전 서버 운행수(user ${U}): ${before} | N=${N} fast=${FAST}`);
  sh(`${ADB} shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1`);sleep(2000);
  let ok=0,fail=0;const rows=[];
  for(let i=0;i<N;i++){
    tap(678,1987);sleep(900);           // 기록 탭
    let ns=dump();
    if(!tapText(ns,'추가')){console.log(`#${i+1} 추가버튼X`);fail++;continue;}
    sleep(1000);
    // 날짜 랜덤
    const dateOk=pickDate();
    ns=dump();
    // 플랫폼/결제 랜덤(칩)
    const plat=pick(PLAT),pay=pick(PAY);
    tapText(ns,plat);sleep(250);ns=dump();tapText(ns,pay);sleep(250);
    // 시간/출발/목적/금액 랜덤
    const hh=rnd(0,23),mm=rnd(0,59),fare=rnd(4000,49000),org=pick(PLACES),dst=pick(PLACES);
    typeInto('시',String(hh));
    typeInto('분',String(mm));
    typeInto('출발지',org);
    typeInto('목적지',dst);
    typeInto('금액',String(fare));
    // 저장 (항상 보임)
    ns=dump();
    if(!tapText(ns,'저장')){console.log(`#${i+1} 저장버튼X`);fail++;continue;}
    sleep(1800);
    ns=dump();
    const fareStr=fare.toLocaleString()+'원';
    const seen=ns.some(n=>n.t&&n.t.replace(/\s/g,'').includes(fareStr.replace(/\s/g,'')));
    rows.push(`#${i+1} date=${dateOk?'rnd':'today'} ${plat}/${pay} ${hh}:${mm} ${org}->${dst} ${fareStr} 목록:${seen?'O':'-'}`);
    if(seen)ok++;else fail++;
    if(!FAST||i<3||i%10===0)console.log(rows[rows.length-1]);
  }
  sleep(1500);
  const after=await tripCount();
  console.log(`\n=== 결과 === UI저장성공(목록노출) ${ok}/${N}, 실패 ${fail} | 서버 ${before}->${after} (증가 ${after-before}, 기대 ${N})`);
  fs.writeFileSync('C:/CallRadar/_test/batch_result.txt',rows.join('\n')+`\n결과: ${ok}/${N} 서버증가 ${after-before}\n`);
})();
