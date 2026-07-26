const {execSync}=require('child_process');
const ADB='C:\\AndroidSdk\\platform-tools\\adb.exe';
const fs=require('fs');
function sh(c){try{return execSync(c,{encoding:'utf8',maxBuffer:1<<26});}catch(e){return e.stdout||'';}}
function sleep(ms){execSync(`${ADB} shell sleep ${ms/1000}`);}
function dump(){sh(`${ADB} shell uiautomator dump /sdcard/ui.xml`);sh(`${ADB} pull /sdcard/ui.xml C:\\CallRadar\\_test\\ui.xml`);return nodes(fs.readFileSync('C:/CallRadar/_test/ui.xml','utf8'));}
function nodes(x){const out=[];const re=/<node ([^>]*?)\/?>/g;let m;while((m=re.exec(x))){const a=m[1];const g=k=>{const mm=a.match(new RegExp(k+'="([^"]*)"'));return mm?mm[1]:'';};const t=g('text'),d=g('content-desc'),b=g('bounds');const bb=b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);let cx=0,cy=0;if(bb){cx=Math.round((+bb[1]+ +bb[3])/2);cy=Math.round((+bb[2]+ +bb[4])/2);}out.push({t,cx,cy});}return out;}
function find(ns,t){return ns.find(n=>n.t===t)||ns.find(n=>n.t&&n.t.includes(t));}
sh(`${ADB} shell monkey -p com.callradar.app -c android.intent.category.LAUNCHER 1`);sleep(2500);
sh(`${ADB} shell input tap 678 1987`);sleep(1200);
let ns=dump();let add=find(ns,'추가');sh(`${ADB} shell input tap ${add.cx} ${add.cy}`);sleep(1200);
// 출발지 찾아 탭 → 'AAA' 입력 → 엔터 → 'BBB' 입력 → 엔터 → 'CCC'
ns=dump();
// 아래 필드 띄우기 위해 스크롤
let org=find(ns,'출발지');
if(!org){sh(`${ADB} shell input swipe 900 1300 900 800 250`);sleep(500);ns=dump();org=find(ns,'출발지');}
sh(`${ADB} shell input tap ${org.cx} ${org.cy}`);sleep(700);
sh(`${ADB} shell input text AAA`);sleep(300);sh(`${ADB} shell input keyevent 66`);sleep(500);
sh(`${ADB} shell input text BBB`);sleep(300);sh(`${ADB} shell input keyevent 66`);sleep(500);
sh(`${ADB} shell input text 12345`);sleep(400);
sh(`${ADB} shell input keyevent 4`);sleep(600); // 키보드 닫기
ns=dump();
console.log('=== 필드 값 확인 (엔터로 다음칸 이동되면 AAA=출발지, BBB=목적지, 12345=금액) ===');
ns.forEach(n=>{if(n.t&&(n.t.includes('AAA')||n.t.includes('BBB')||n.t.includes('12345')||n.t.includes('출발')||n.t.includes('목적')||n.t.includes('금액')))console.log(`[${n.cx},${n.cy}] ${n.t}`);});
