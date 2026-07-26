const fs = require('fs');
const path = require('path');
const screenDir = 'C:/CallRadar/app/src/main/java/com/callradar/app/screen';
const mainAct = 'C:/CallRadar/app/src/main/java/com/callradar/app/MainActivity.kt';

const A = '= "https://callradar-server.onrender.com"';
const B = '= Config.SERVER_URL';

function processFile(file, isMain) {
  let s = fs.readFileSync(file, 'utf8');
  const n = s.split(A).length - 1;
  if (n === 0) { return; }
  s = s.split(A).join(B);
  if (isMain && !s.includes('import com.callradar.app.screen.Config')) {
    s = s.replace('import com.callradar.app.screen.AppTheme',
      'import com.callradar.app.screen.AppTheme\nimport com.callradar.app.screen.Config');
  }
  fs.writeFileSync(file, s, 'utf8');
  console.log(path.basename(file), 'replaced', n);
}

for (const f of fs.readdirSync(screenDir)) {
  if (f.endsWith('.kt') && f !== 'Config.kt') processFile(path.join(screenDir, f), false);
}
processFile(mainAct, true);
console.log('DONE');
