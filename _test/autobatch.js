// 앱 UI를 실제로 탭/입력/저장하는 자동 배치 (사람처럼). 폴더블 해상도 대응: 매번 화면 덤프로 좌표 탐색.
const {execSync}=require('child_process');
const ADB='C:\\AndroidSdk\\platform-tools\\adb.exe';
const fs=require('fs');
const S='https://callradar-server.onrender.com';
const U=205; // 서브폰 게스트
const N=parseInt(process.argv[2]||'3',10);
function sh(c){try{return execSync(c,{encoding:'utf8',maxBuffer:1<<26});}catch(e){return e.stdout||'';}}
function sleep(ms){execSync(`${ADB} shell sleep ${ms/1000}`);}
function dump(){sh(`${ADB} shell uiautomator dump /sdcard/ui.xml`);sh(`${ADB} pull /sdcard/ui.xml C:\\CallRadar\\_test\\ui.xml`);return nodes(fs.readFileSync('C:/CallRadar/_test/ui.xml','utf8'));}
function nodes(x){const out=[];const re=/<node ([^>]*?)\/?>/g;let m;while((m=re.exec(x))){const a=m[1];const g=k=>{const mm=a.match(new RegExp(k+'="([^"]*)"'));return mm?mm[1]:'';};const t=g('text'),d=g('content-desc'),b=g('bounds');const bb=b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);let cx=0,cy=0;if(bb){cx=Math.round((+bb[1]+ +bb[3])/2);cy=Math.round((+bb[2]+ +bb[4])/2);}out.push({t,d,cx,cy});}return out;}
function find(ns,txt){return ns.find(n=>n.t===txt)||ns.find(n=>n.t&&n.t.includes(txt));}
function tap(x,y){sh(`${ADB} shell input tap ${x} ${y}`);}
function tapText(ns,txt){const n=find(ns,txt);if(n){tap(n.cx,n.cy);return true;}return false;}
function back(){sh(`${ADB} shell input keyevent 4`);}
function typeInto(labelText,value){ // dump, find field by label, tap, type, dismiss keyboard
  let ns=dump();const n=find(ns,labelText);if(!n){return false;}
  tap(n.cx,n.cy);sleep(600);sh(`${ADB} shell input text ${value}`);sleep(400);back();sleep(500);return true;}
const rnd=(a,b)=>a+Math.floor(Math.random()*(b-a+1));
const pick=a=>a[Math.floor(Math.random()*a.length)];
const PLAT=['카카오T','우버','티머니고','길빵/예약'];
const PAY=['카드','현금','자동결제'];
const PLACES=['GangnamStn','ICN-Airport','Pangyo','SeoulStn','Hongdae','Suwon','Ilsan','Jamsil','Myeongdong','Sindorim'];

async function tripCount(){try{const r=await fetch(`${S}/api/trips/${U}?limit=500`);const j=await r.json();return Array.isArray(j)?j.length:(j.trips?j.trips.length:0);}catch(e){return -1;}}

(async()=>{
  const before=await tripCount();
  console.log(`시작 전 서버 운행수(user ${U}): ${before}`);
  sh(`${ADB} shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1`);sleep(2000);
  let ok=0,fail=0;
  for(let i=0;i<N;i++){
    // 기록 탭 → 추가
    tap(678,1987);sleep(1200);
    let ns=dump();
    if(!tapText(ns,'추가')){console.log(`#${i+1} 추가버튼 없음`);fail++;continue;}
    sleep(1200);ns=dump();
    // 플랫폼/결제 랜덤 (칩 - 키보드 불필요)
    const plat=pick(PLAT),pay=pick(PAY);
    tapText(ns,plat);sleep(300);ns=dump();tapText(ns,pay);sleep(300);
    // 시간/출발/목적/금액 랜덤 (필드별 덤프+타이핑+키보드닫기)
    const hh=rnd(0,23),mm=rnd(0,59),fare=rnd(4000,48000);
    typeInto('시',String(hh));
    typeInto('분',String(mm));
    typeInto('출발지',pick(PLACES));
    typeInto('목적지',pick(PLACES));
    typeInto('금액',String(fare));
    // 저장 버튼이 스크롤 하단 → 위로 스와이프해서 화면에 띄운 뒤 탭
    sh(`${ADB} shell input swipe 900 1500 900 500 300`);sleep(700);
    ns=dump();
    let sv=find(ns,'저장');
    if(!sv){sh(`${ADB} shell input swipe 900 1600 900 400 300`);sleep(700);ns=dump();sv=find(ns,'저장');}
    if(!sv){console.log(`#${i+1} 저장버튼 없음`);fail++;continue;}
    tap(sv.cx,sv.cy);sleep(2200);
    // 검증: 목록에 방금 금액이 뜨는지
    ns=dump();
    const fareStr=fare.toLocaleString()+'원';
    const seen=ns.some(n=>n.t&&n.t.replace(/\s/g,'')===fareStr.replace(/\s/g,''));
    console.log(`#${i+1} plat=${plat} pay=${pay} ${hh}:${mm} fare=${fareStr} → 목록노출:${seen?'O':'-'}`);
    if(seen)ok++;else fail++;
  }
  sleep(1500);
  const after=await tripCount();
  console.log(`\n=== 결과 ===`);
  console.log(`UI 저장 성공(목록노출): ${ok}/${N}, 실패 ${fail}`);
  console.log(`서버 운행수: ${before} → ${after} (증가 ${after-before}, 기대 ${N})`);
})();
