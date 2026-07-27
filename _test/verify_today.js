const {execSync}=require('child_process');
const ADB='C:\\AndroidSdk\\platform-tools\\adb.exe';
const fs=require('fs');
function sh(c){try{return execSync(c,{encoding:'utf8',maxBuffer:1<<26});}catch(e){return e.stdout||'';}}
function sleep(ms){execSync(`${ADB} shell sleep ${ms/1000}`);}
function dump(){sh(`${ADB} shell uiautomator dump /sdcard/ui.xml`);sh(`${ADB} pull /sdcard/ui.xml C:\\CallRadar\\_test\\ui.xml`);return nodes(fs.readFileSync('C:/CallRadar/_test/ui.xml','utf8'));}
function nodes(x){const out=[];const re=/<node ([^>]*?)\/?>/g;let m;while((m=re.exec(x))){const a=m[1];const g=k=>{const mm=a.match(new RegExp(k+'="([^"]*)"'));return mm?mm[1]:'';};const t=g('text'),b=g('bounds');const bb=b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);let cx=0,cy=0;if(bb){cx=Math.round((+bb[1]+ +bb[3])/2);cy=Math.round((+bb[2]+ +bb[4])/2);}out.push({t,cx,cy});}return out;}
function find(ns,t){return ns.find(n=>n.t===t)||ns.find(n=>n.t&&n.t.includes(t));}
function tapT(ns,t){const n=find(ns,t);if(n){sh(`${ADB} shell input tap ${n.cx} ${n.cy}`);return true;}return false;}
sh(`${ADB} shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1`);sleep(2500);
sh(`${ADB} shell input tap 678 1987`);sleep(1200);          // 기록
let ns=dump();tapT(ns,'추가');sleep(1200);                   // 추가 (날짜=오늘 기본)
ns=dump();tapT(ns,'카카오T');sleep(300);tapT(dump(),'현금');sleep(300);
// 금액 필드까지 스크롤 후 입력
sh(`${ADB} shell input swipe 900 1300 900 700 250`);sleep(500);
ns=dump();let f=find(ns,'금액');
if(f){sh(`${ADB} shell input tap ${f.cx} ${f.cy}`);sleep(600);sh(`${ADB} shell input text 88888`);sleep(400);sh(`${ADB} shell input keyevent 4`);sleep(500);}
else console.log('금액 필드 못찾음');
// 저장
ns=dump();tapT(ns,'저장');sleep(2200);
// 저장 후 목록/필터 확인
ns=dump();
const filterOtoday=ns.some(n=>n.t==='오늘');
const seen=ns.some(n=>n.t&&n.t.replace(/\s/g,'').includes('77,777'.replace(/\s/g,''))||n.t&&n.t.includes('88888'));
console.log('저장 후 필터가 오늘?', filterOtoday);
console.log('목록에 77,777원 보임?', seen);
console.log('--- 상단 기록 몇 개 ---');
ns.filter(n=>n.t&&n.t.includes('원')).slice(0,6).forEach(n=>console.log(n.t));
