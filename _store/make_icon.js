const sharp = require('sharp');
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">
<rect width="108" height="108" fill="#0A0E1A"/>
<circle cx="54" cy="54" r="34" fill="none" stroke="#F59E0B" stroke-width="2"/>
<circle cx="54" cy="54" r="24" fill="none" stroke="#F59E0B" stroke-width="1.5" stroke-opacity="0.6"/>
<circle cx="54" cy="54" r="14" fill="none" stroke="#F59E0B" stroke-width="1" stroke-opacity="0.3"/>
<line x1="54" y1="54" x2="75" y2="33" stroke="#F59E0B" stroke-width="2"/>
<circle cx="54" cy="54" r="3" fill="#F59E0B"/>
<circle cx="72" cy="36" r="3" fill="#10B981"/>
<circle cx="45" cy="65" r="2" fill="#F59E0B" fill-opacity="0.5"/>
<path d="M62,80 A10,8 0 1,1 62,96" fill="none" stroke="#F59E0B" stroke-width="2.5"/>
</svg>`;
sharp(Buffer.from(svg)).resize(512, 512).png()
  .toFile('C:\\CallRadar\\_store\\onestore_icon_512.png')
  .then(() => console.log('SAVED 512 icon'))
  .catch(e => { console.error(e); process.exit(1); });
