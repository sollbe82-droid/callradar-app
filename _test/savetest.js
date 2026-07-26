const {execSync}=require('child_process');
const ADB='C:\\AndroidSdk\\platform-tools\\adb.exe';
const fs=require('fs');
function sh(c){try{return execSync(c,{encoding:'utf8',maxBuffer:1<<26});}catch(e){return e.stdout||'';}}
function sleep(ms){execSync(`${ADB} shell sleep ${ms/1000}`);}
function dump(){sh(`${ADB} shell uiautomator dump /sdcard/ui.xml`);sh(`${ADB} pull /sdcard/ui.xml C:\\CallRadar\\_test\\ui.xml`);return fs.readFileSync('C:/CallRadar/_test/ui.xml','utf8');}
function nodes(x){const out=[];const re=/<node ([^>]*?)\/?>/g;let m;while((m=re.exec(x))){const a=m[1];const g=k=>{const mm=a.match(new RegExp(k+'="([^"]*)"'));return mm?mm[1]:'';};const t=g('text'),d=g('content-desc'),b=g('bounds');const bb=b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);let cx=0,cy=0;if(bb){cx=Math.round((+bb[1]+ +bb[3])/2);cy=Math.round((+bb[2]+ +bb[4])/2);}out.push({t,d,cx,cy});}return out;}
function find(ns,txt){return ns.find(n=>n.t===txt)||ns.find(n=>n.t&&n.t.includes(txt));}
// tap 저장
let ns=nodes(dump());let save=find(ns,'저장');
console.log('저장 at',save?`${save.cx},${save.cy}`:'NOT FOUND');
if(save){sh(`${ADB} shell input tap ${save.cx} ${save.cy}`);}
sleep(2500);
console.log('=== 기록 목록 (저장 후) ===');
ns=nodes(dump());
ns.forEach(n=>{if(n.t)console.log(`[${n.cx},${n.cy}] ${n.t}`);});
