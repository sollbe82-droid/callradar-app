const {execSync}=require('child_process');
const ADB='C:\\AndroidSdk\\platform-tools\\adb.exe';
const fs=require('fs');
function sh(c){try{return execSync(c,{encoding:'utf8',maxBuffer:1<<26});}catch(e){return e.stdout||'';}}
function sleep(ms){execSync(`${ADB} shell sleep ${ms/1000}`);}
function dump(){sh(`${ADB} shell uiautomator dump /sdcard/ui.xml`);sh(`${ADB} pull /sdcard/ui.xml C:\\CallRadar\\_test\\ui.xml`);return fs.readFileSync('C:/CallRadar/_test/ui.xml','utf8');}
function nodes(x){const out=[];const re=/<node ([^>]*?)\/?>/g;let m;while((m=re.exec(x))){const a=m[1];const g=k=>{const mm=a.match(new RegExp(k+'="([^"]*)"'));return mm?mm[1]:'';};const t=g('text'),d=g('content-desc'),cl=g('class'),b=g('bounds');const bb=b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);let cx=0,cy=0;if(bb){cx=Math.round((+bb[1]+ +bb[3])/2);cy=Math.round((+bb[2]+ +bb[4])/2);}out.push({t,d,cl,cx,cy});}return out;}
function pr(ns){ns.forEach(n=>{if(n.t||n.d)console.log(`[${n.cx},${n.cy}] | ${n.t}${n.d?' d='+n.d:''}`);});}
// tap 금액 field, type
sh(`${ADB} shell input tap 354 1711`);sleep(900);
sh(`${ADB} shell input text 15000`);sleep(800);
console.log('=== after type 금액 ===');
pr(nodes(dump()));
