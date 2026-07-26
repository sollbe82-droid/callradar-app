const H = require('./H');
H.sh(H.ADB + ' shell am force-stop com.callradar.app'); H.sleep(1200);
H.sh(H.ADB + ' shell am start -n com.callradar.app/.MainActivity'); H.sleep(6500);
H.shot('50_home_month.png');
H.tap('기록'); H.sleep(1200);
H.tap('월별'); H.sleep(1800);
H.shot('51_monthly.png');
console.log('DONE');
