const {execSync}=require('child_process');
const ADB='C:\\AndroidSdk\\platform-tools\\adb.exe';
function sh(c){return execSync(c,{encoding:'utf8',maxBuffer:1<<26});}
sh(`${ADB} shell uiautomator dump /sdcard/ui.xml`);
sh(`${ADB} pull /sdcard/ui.xml C:\\CallRadar\\_test\\ui.xml`);
const fs=require('fs');
const x=fs.readFileSync('C:/CallRadar/_test/ui.xml','utf8');
const nodes=[...x.matchAll(/<node [^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g)];
// full node parse
const re=/<node ([^>]*?)\/?>/g;let m;
while((m=re.exec(x))){
  const a=m[1];
  const g=k=>{const mm=a.match(new RegExp(k+'="([^"]*)"'));return mm?mm[1]:'';};
  const t=g('text'),d=g('content-desc'),cl=g('class'),ck=g('clickable'),b=g('bounds');
  if(t||d){
    let cx='',cy='';const bb=b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if(bb){cx=Math.round((+bb[1]+ +bb[3])/2);cy=Math.round((+bb[2]+ +bb[4])/2);}
    console.log(`[${cx},${cy}] click=${ck} ${cl.replace('android.widget.','').replace('androidx.compose.ui.platform.','')} | ${t}${d?' desc='+d:''}`);
  }
}
