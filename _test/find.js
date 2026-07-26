const fs = require('fs');
const x = fs.readFileSync('C:/CallRadar/_test/ui.xml', 'utf8');
const target = process.argv[2] || '';
const re = /<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
let m;
while (m = re.exec(x)) {
  if (!m[1]) continue;
  if (!target || m[1].includes(target)) {
    const cx = ((+m[2]) + (+m[4])) / 2 | 0, cy = ((+m[3]) + (+m[5])) / 2 | 0;
    console.log(m[1] + ' => ' + cx + ',' + cy);
  }
}
