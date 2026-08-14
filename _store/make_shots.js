const sharp = require('sharp');
const path = require('path');
const fs = require('fs');

const SRC = 'C:\\CallRadar\\_store\\raw';
const OUT = 'C:\\CallRadar\\_store\\screenshots';
fs.mkdirSync(OUT, { recursive: true });

const W = 731, H = 1300;          // exact 9:16, within 1300px max
const NAVY = { r: 10, g: 14, b: 26, alpha: 1 }; // #0A0E1A

async function run() {
  for (let i = 1; i <= 5; i++) {
    const src = path.join(SRC, `raw${i}.jpg`);
    if (!fs.existsSync(src)) continue;
    // resize to fit inside 731x1300 keeping aspect, pad with navy
    const buf = await sharp(src)
      .resize(W, H, { fit: 'contain', background: NAVY })
      .png({ compressionLevel: 9 })
      .toBuffer();
    const outPath = path.join(OUT, `callradar_shot_${i}.png`);
    fs.writeFileSync(outPath, buf);
    const kb = (buf.length / 1024).toFixed(0);
    console.log(`shot_${i}.png  ${W}x${H}  ${kb}KB`);
  }
  console.log('DONE');
}
run().catch(e => { console.error(e); process.exit(1); });
