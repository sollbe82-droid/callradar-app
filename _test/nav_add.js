const H = require('./H');
H.tap('기록');
let xml = H.dumpXml();
if (H.center(xml, '운행기록')) H.tap('운행기록');
H.tap('추가');            // + 추가 → 운행 추가 다이얼로그
H.shot('40_add_dialog.png');
xml = H.dumpXml();
console.log('=== EditText 필드 ===');
H.editFields(xml).forEach((e, i) => console.log(i, 'text=' + JSON.stringify(e.tx), 'desc=' + JSON.stringify(e.cd), '@' + (e.c ? e.c.join(',') : '')));
console.log('=== 라벨(금액/목적지/출발/팁) 위치 ===');
H.nodes(xml).filter(n => /금액|목적지|출발|팁/.test(n.tx + n.cd)).forEach(n => console.log(JSON.stringify(n.tx || n.cd), '@' + (n.c ? n.c.join(',') : '')));
console.log('DONE');
