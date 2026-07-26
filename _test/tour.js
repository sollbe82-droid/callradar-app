// 콜레이더 스모크 테스트 하네스 (Node) — 화면 글자로 버튼을 찾아 탭 (해상도/기종 무관)
const { execSync } = require('child_process');
const fs = require('fs');
const ADB = '"C:\\AndroidSdk\\platform-tools\\adb.exe"';
const DIR = 'C:\\CallRadar\\_test';
function sh(c) { try { return execSync(c, { encoding: 'utf8' }); } catch (e) { return (e.stdout || '') + (e.stderr || ''); } }
function sleep(ms) { try { execSync('ping -n ' + (Math.ceil(ms / 1000) + 1) + ' 127.0.0.1 >nul'); } catch (e) {} }
function dump() { sh(ADB + ' shell uiautomator dump /sdcard/ui.xml'); sh(ADB + ' pull /sdcard/ui.xml ' + DIR + '\\ui.xml'); try { return fs.readFileSync(DIR + '\\ui.xml', 'utf8'); } catch (e) { return ''; } }
function findCenter(xml, t) {
  for (const n of xml.split('<node')) {
    const tx = (n.match(/ text="([^"]*)"/) || [])[1] || '';
    const cd = (n.match(/content-desc="([^"]*)"/) || [])[1] || '';
    if (tx.includes(t) || cd.includes(t)) {
      const b = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
      if (b) return [Math.round((+b[1] + +b[3]) / 2), Math.round((+b[2] + +b[4]) / 2)];
    }
  }
  return null;
}
function tapText(t) {
  const c = findCenter(dump(), t);
  if (c) { sh(ADB + ' shell input tap ' + c[0] + ' ' + c[1]); console.log('TAP  ' + t + ' @' + c.join(',')); }
  else { console.log('MISS ' + t); }
  sleep(1800);
}
function back() { sh(ADB + ' shell input keyevent 4'); sleep(1500); }
function shot(name) { sh(ADB + ' shell screencap -p /sdcard/s.png'); sh(ADB + ' pull /sdcard/s.png ' + DIR + '\\' + name); console.log('SHOT ' + name); }

sh(ADB + ' logcat -c');           // 크래시 감지용 로그 초기화
shot('10_home.png');
tapText('기록'); shot('11_records.png');
tapText('가져오기'); shot('12_import.png');   // 실적 가져오기(카메라/갤러리/파일)
back();
tapText('공항'); shot('13_airport.png');
tapText('더보기'); shot('14_more.png');
tapText('기사 설정'); shot('15_settings.png'); // 홈/더보기 기사설정 → 정산설정 진입 검증
back(); shot('16_back_from_sub.png');          // 하위→더보기홈 (뒤로가기 수정 검증)
back(); shot('17_back_to_home.png');           // 탭→홈 (앱종료 안됨 검증)
console.log('DONE');
