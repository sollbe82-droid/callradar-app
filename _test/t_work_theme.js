const { execSync } = require('child_process');
const fs = require('fs');
const ADB = '"C:\\AndroidSdk\\platform-tools\\adb.exe"';
const DIR = 'C:\\CallRadar\\_test';
function sh(c){ try{ return execSync(c,{encoding:'utf8'});}catch(e){ return (e.stdout||'')+(e.stderr||'');}}
function sleep(ms){ try{ execSync('ping -n '+(Math.ceil(ms/1000)+1)+' 127.0.0.1 >nul'); }catch(e){} }
function dump(){ sh(ADB+' shell uiautomator dump /sdcard/ui.xml'); sh(ADB+' pull /sdcard/ui.xml '+DIR+'\\ui.xml'); try{return fs.readFileSync(DIR+'\\ui.xml','utf8');}catch(e){return '';} }
function center(xml,t){ for(const n of xml.split('<node')){ const tx=(n.match(/ text="([^"]*)"/)||[])[1]||''; const cd=(n.match(/content-desc="([^"]*)"/)||[])[1]||''; if(tx.includes(t)||cd.includes(t)){ const b=n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/); if(b) return [Math.round((+b[1]+ +b[3])/2),Math.round((+b[2]+ +b[4])/2)];}} return null; }
function tap(t){ const c=center(dump(),t); if(c){ sh(ADB+' shell input tap '+c[0]+' '+c[1]); console.log('TAP '+t+' @'+c.join(','));} else console.log('MISS '+t); sleep(1800); }
function shot(n){ sh(ADB+' shell screencap -p /sdcard/s.png'); sh(ADB+' pull /sdcard/s.png '+DIR+'\\'+n); console.log('SHOT '+n); }

sh(ADB+' logcat -c');
tap('출근'); shot('22_work_on.png');
sleep(2500);
tap('퇴근'); shot('23_work_summary.png');
tap('확인');
tap('더보기'); sleep(1000);
tap('화면 테마'); shot('24_more_light.png');
tap('화면 테마'); // 다시 다크로 원복
console.log('DONE');
