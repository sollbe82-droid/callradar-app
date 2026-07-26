const H = require('./H');
H.tap('기록'); H.sleep(1000);
H.tap('가져오기'); H.sleep(1500);
H.tap('직접 추가'); H.sleep(1200);
let xml = H.dumpXml();
let edits = H.editFields(xml).sort((a, b) => (a.c[1] - b.c[1]) || (a.c[0] - b.c[0]));
console.log('EditText 수=' + edits.length + ' 좌표=' + JSON.stringify(edits.map(e => e.c)));
// 한 행: [일, 수입, 지출] (x 오름차순). 수입=가운데
if (edits.length >= 2) {
  const income = edits.slice().sort((a, b) => a.c[0] - b.c[0])[1];
  H.tapXY(income.c[0], income.c[1]); H.sleep(500);
  H.type('350000'); H.sleep(500);
  H.sh(H.ADB + ' shell input keyevent 111'); // ESC 키로 키보드 닫기 시도
  H.sleep(500);
}
H.shot('61_import_filled.png');
H.tap('이 내용으로 가져오기'); H.sleep(3000);
H.shot('62_import_done.png');
console.log('DONE');
