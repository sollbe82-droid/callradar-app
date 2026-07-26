const fs = require('fs');
const path = require('path');
const screenDir = 'C:/CallRadar/app/src/main/java/com/callradar/app/screen';
const mainAct = 'C:/CallRadar/app/src/main/java/com/callradar/app/MainActivity.kt';

const repl = [
  ['Color(0xFF0A0E1A)', 'AppTheme.bg'],
  ['Color(0xFF111827)', 'AppTheme.card'],
  ['Color(0xFF1F2937)', 'AppTheme.surface2'],
  ['color = Color.White', 'color = AppTheme.text'],
  ['TextColor = Color.White', 'TextColor = AppTheme.text'],
];

function processFile(file, isMain) {
  let s = fs.readFileSync(file, 'utf8');
  let counts = {};
  for (const [a, b] of repl) {
    const parts = s.split(a);
    if (parts.length > 1) { counts[a] = parts.length - 1; s = parts.join(b); }
  }
  if (isMain && !s.includes('import com.callradar.app.screen.AppTheme')) {
    s = s.replace('import com.callradar.app.ui.theme.CallRadarTheme',
      'import com.callradar.app.ui.theme.CallRadarTheme\nimport com.callradar.app.screen.AppTheme');
    counts['+import'] = 1;
  }
  fs.writeFileSync(file, s, 'utf8');
  console.log(path.basename(file), JSON.stringify(counts));
}

for (const f of fs.readdirSync(screenDir)) {
  if (f.endsWith('.kt') && f !== 'AppTheme.kt') processFile(path.join(screenDir, f), false);
}
processFile(mainAct, true);
console.log('DONE');
