const fs = require('fs');
const x = fs.readFileSync('C:/CallRadar/_test/ui.xml', 'utf8');
const re = /<node[^>]*class="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
let m;
while (m = re.exec(x)) {
  if (m[1].includes('EditText') || m[1].includes('Button')) {
    const seg = x.slice(m.index, m.index + 400);
    const t = (seg.match(/text="([^"]*)"/) || [])[1] || '';
    const cx = ((+m[2]) + (+m[4])) / 2 | 0, cy = ((+m[3]) + (+m[5])) / 2 | 0;
    console.log(m[1].split('.').pop() + ' [' + t + '] => ' + cx + ',' + cy);
  }
}
