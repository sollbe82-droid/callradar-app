const {execSync}=require('child_process');
const ADB='C:\\AndroidSdk\\platform-tools\\adb.exe';
const fs=require('fs');
function sh(c){try{return execSync(c,{encoding:'utf8',maxBuffer:1<<26});}catch(e){return e.stdout||'';}}
function sleep(ms){execSync(`${ADB} shell sleep ${ms/1000}`);}
function dump(){sh(`${ADB} shell uiautomator dump /sdcard/ui.xml`);sh(`${ADB} pull /sdcard/ui.xml C:\\CallRadar\\_test\\ui.xml`);return fs.readFileSync('C:/CallRadar/_test/ui.xml','utf8');}
function nodes(x){const out=[];const re=/<node ([^>]*?)\/?>/g;let m;while((m=re.exec(x))){const a=m[1];const g=k=>{const mm=a.match(new RegExp(k+'="([^"]*)"'));return mm?mm[1]:'';};const t=g('text'),d=g('content-desc'),b=g('bounds');const bb=b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);let cx=0,cy=0,x2=0,y2=0;if(bb){cx=Math.round((+bb[1]+ +bb[3])/2);cy=Math.round((+bb[2]+ +bb[4])/2);x2=+bb[3];y2=+bb[4];}out.push({t,d,cx,cy,y2});}return out;}
function find(ns,txt){return ns.find(n=>n.t===txt)||ns.find(n=>n.t&&n.t.includes(txt));}
// 1) dismiss keyboard: press BACK once (Compose: first back hides IME)
sh(`${ADB} shell input keyevent 4`);sleep(1200);
let ns=nodes(dump());
// confirm dialog still open (금액 label present) and find 저장
const hasDialog=find(ns,'금액');
const save=find(ns,'저장');
console.log('dialog open?',!!hasDialog,'| 저장 at',save?`${save.cx},${save.cy}`:'NONE');
if(hasDialog&&save){
  sh(`${ADB} shell input tap ${save.cx} ${save.cy}`);sleep(2500);
  console.log('--- after 저장 ---');
  ns=nodes(dump());
  const stillDialog=find(ns,'금액 (원)')||find(ns,'운행 추가');
  console.log('dialog still open?',!!stillDialog);
  ns.forEach(n=>{if(n.t)console.log(`[${n.cx},${n.cy}] ${n.t}`);});
} else { console.log('cannot save; dialog state unexpected'); ns.forEach(n=>{if(n.t)console.log(n.t);}); }
