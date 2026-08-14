const sharp = require('sharp');

const radar = `
<g transform="translate(70,160) scale(2.4)">
  <circle cx="54" cy="54" r="34" fill="none" stroke="#F59E0B" stroke-width="2"/>
  <circle cx="54" cy="54" r="24" fill="none" stroke="#F59E0B" stroke-width="1.5" stroke-opacity="0.6"/>
  <circle cx="54" cy="54" r="14" fill="none" stroke="#F59E0B" stroke-width="1" stroke-opacity="0.3"/>
  <line x1="54" y1="54" x2="75" y2="33" stroke="#F59E0B" stroke-width="2"/>
  <circle cx="54" cy="54" r="3" fill="#F59E0B"/>
  <circle cx="72" cy="36" r="3.4" fill="#10B981"/>
  <circle cx="45" cy="65" r="2" fill="#F59E0B" fill-opacity="0.5"/>
</g>`;

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="578" viewBox="0 0 1024 578">
  <defs>
    <radialGradient id="bg" cx="30%" cy="40%" r="90%">
      <stop offset="0%" stop-color="#141B2E"/>
      <stop offset="100%" stop-color="#0A0E1A"/>
    </radialGradient>
  </defs>
  <rect width="1024" height="578" fill="url(#bg)"/>
  ${radar}
  <text x="410" y="250" font-family="Malgun Gothic, sans-serif" font-size="104" font-weight="bold" fill="#F59E0B">콜레이더</text>
  <text x="412" y="322" font-family="Malgun Gothic, sans-serif" font-size="56" font-weight="bold" fill="#FFFFFF">택시의 신</text>
  <rect x="414" y="352" width="70" height="4" fill="#10B981"/>
  <text x="412" y="410" font-family="Malgun Gothic, sans-serif" font-size="32" fill="#C9D1E0">자동 운행기록 · 수입정산 · 콜 레이더</text>
  <text x="412" y="452" font-family="Malgun Gothic, sans-serif" font-size="26" fill="#8A94A6">택시 기사를 위한 스마트 매출 관리</text>
</svg>`;

sharp(Buffer.from(svg)).png()
  .toFile('C:\\CallRadar\\_store\\onestore_banner_1024x578.png')
  .then(() => console.log('SAVED banner'))
  .catch(e => { console.error(e); process.exit(1); });
