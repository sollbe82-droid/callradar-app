// 공용 하네스 함수
const { execSync } = require('child_process');
const fs = require('fs');
const ADB = '"C:\\AndroidSdk\\platform-tools\\adb.exe"';
const DIR = 'C:\\CallRadar\\_test';
function sh(c){ try{ return execSync(c,{encoding:'utf8'});}catch(e){ return (e.stdout||'')+(e.stderr||'');}}
function sleep(ms){ try{ execSync('ping -n '+(Math.ceil(ms/1000)+1)+' 127.0.0.1 >nul'); }catch(e){} }
function dumpXml(){ sh(ADB+' shell uiautomator dump /sdcard/ui.xml'); sh(ADB+' pull /sdcard/ui.xml '+DIR+'\\ui.xml'); try{return fs.readFileSync(DIR+'\\ui.xml','utf8');}catch(e){return '';} }
function nodes(xml){ return xml.split('<node').map(n=>{ const tx=(n.match(/ text="([^"]*)"/)||[])[1]||''; const cd=(n.match(/content-desc="([^"]*)"/)||[])[1]||''; const cl=(n.match(/ class="([^"]*)"/)||[])[1]||''; const b=n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/); const c=b?[Math.round((+b[1]+ +b[3])/2),Math.round((+b[2]+ +b[4])/2)]:null; return {tx,cd,cl,c,b:b?[+b[1],+b[2],+b[3],+b[4]]:null}; }); }
function center(xml,t){ const ns=nodes(xml); for(const n of ns){ if(n.c&&(n.tx===t||n.cd===t)) return n.c;} for(const n of ns){ if(n.c&&(n.tx.includes(t)||n.cd.includes(t))) return n.c;} return null; }
function tap(t){ const c=center(dumpXml(),t); if(c){ sh(ADB+' shell input tap '+c[0]+' '+c[1]); console.log('TAP '+t+' @'+c.join(',')); } else console.log('MISS '+t); sleep(1600); }
function tapXY(x,y){ sh(ADB+' shell input tap '+x+' '+y); sleep(1200); }
function type(s){ sh(ADB+' shell input text '+s); sleep(600); }
function back(){ sh(ADB+' shell input keyevent 4'); sleep(1300); }
function shot(n){ sh(ADB+' shell screencap -p /sdcard/s.png'); sh(ADB+' pull /sdcard/s.png '+DIR+'\\'+n); console.log('SHOT '+n); }
function editFields(xml){ return nodes(xml).filter(n=>n.cl.includes('EditText')&&n.c); }
module.exports={sh,sleep,dumpXml,nodes,center,tap,tapXY,type,back,shot,editFields,ADB,DIR};
