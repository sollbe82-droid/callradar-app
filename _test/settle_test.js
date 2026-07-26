const H = require('./H');
H.sh(H.ADB + ' shell am force-stop com.callradar.app'); H.sleep(1200);
H.sh(H.ADB + ' shell am start -n com.callradar.app/.MainActivity'); H.sleep(6500);
H.tap('기사 설정'); H.sleep(1500);      // 홈 → 정산 설정
H.shot('70_settle.png');
H.tap('기사 유형'); H.sleep(1300);       // 기사 유형 다이얼로그
H.shot('71_typedialog.png');
H.tap('법인기사'); H.sleep(800);
H.tap('저장'); H.sleep(1500);
H.shot('72_after_type.png');
// 사납금 카드가 생겼으면 탭 → 12만 → 저장
let xml = H.dumpXml();
if (H.center(xml, '사납금')) {
  H.tap('사납금'); H.sleep(1200);
  H.tap('12만'); H.sleep(600);
  H.tap('저장'); H.sleep(1500);
  H.shot('73_after_sanap.png');
} else { console.log('사납금 카드 없음 (스샷 확인 필요)'); }
console.log('DONE');
