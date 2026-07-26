const H = require('./H');
H.back(); H.sleep(1200);                 // 키보드 닫기
H.shot('63_before_import.png');
H.tap('이 내용으로 가져오기'); H.sleep(3500);
H.shot('64_import_result.png');
H.tap('닫기'); H.sleep(1000);            // 가져오기 화면 닫기
H.sh(H.ADB + ' shell input keyevent 4'); H.sleep(1000); // 홈으로
H.tap('홈'); H.sleep(2500);
H.shot('65_home_after_import.png');
console.log('DONE');
