const {execSync}=require('child_process');
const ADB='C:\\AndroidSdk\\platform-tools\\adb.exe';
const fs=require('fs');
function sh(c){try{return execSync(c,{encoding:'utf8',maxBuffer:1<<26});}catch(e){return e.stdout||'';}}
function sleep(ms){execSync(`${ADB} shell sleep ${ms/1000}`);}
function dump(){sh(`${ADB} shell uiautomator dump /sdcard/ui.xml`);sh(`${ADB} pull /sdcard/ui.xml C:\\CallRadar\\_test\\ui.xml`);return fs.readFileSync('C:/CallRadar/_test/ui.xml','utf8');}
function nodes(x){const out=[];const re=/<node ([^>]*?)\/?>/g;let m;while((m=re.exec(x))){const a=m[1];const g=k=>{const mm=a.match(new RegExp(k+'="([^"]*)"'));return mm?mm[1]:'';};const t=g('text'),d=g('content-desc'),cl=g('class'),ck=g('clickable'),b=g('bounds');const bb=b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);let cx=0,cy=0;if(bb){cx=Math.round((+bb[1]+ +bb[3])/2);cy=Math.round((+bb[2]+ +bb[4])/2);}out.push({t,d,cl,ck,cx,cy});}return out;}
function tap(x,y){sh(`${ADB} shell input tap ${x} ${y}`);}
function find(ns,txt){return ns.find(n=>n.t===txt)||ns.find(n=>n.t&&n.t.includes(txt))||ns.find(n=>n.d&&n.d.includes(txt));}
// go 기록
tap(678,1987);sleep(1500);
let ns=nodes(dump());
let add=find(ns,'추가');
console.log('추가 node:',add?`[${add.cx},${add.cy}] ${add.t}`:'NOT FOUND');
if(add){tap(add.cx,add.cy);sleep(1500);ns=nodes(dump());}
console.log('=== DIALOG NODES ===');
ns.forEach(n=>{if(n.t||n.d)console.log(`[${n.cx},${n.cy}] ${n.cl.replace('android.widget.','')} | ${n.t}${n.d?' desc='+n.d:''}`);});
