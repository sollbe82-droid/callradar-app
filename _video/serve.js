// 로컬 전용 임시 파일 서버 — 유튜브 업로드용으로만 쓰고 바로 끈다.
// 127.0.0.1 에만 바인딩하므로 외부에서 접근 불가.
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = process.argv[2] || 'C:\\CallRadar\\_video';
const PORT = Number(process.argv[3] || 8899);

http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  // ★ Chrome Private Network Access — 이게 없으면 https 페이지에서 127.0.0.1 호출이
  //   프리플라이트에서 조용히 막힌다(에러도 안 나고 그냥 멈춘다).
  res.setHeader('Access-Control-Allow-Private-Network', 'true');
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }

  const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '');
  if (rel === '__ping') { res.writeHead(200); return res.end('ok'); }

  const full = path.resolve(ROOT, rel);
  if (!full.startsWith(path.resolve(ROOT))) { res.writeHead(403); return res.end('no'); }
  fs.stat(full, (e, st) => {
    if (e || !st.isFile()) { res.writeHead(404); return res.end('nf'); }
    res.writeHead(200, {
      'Content-Type': full.endsWith('.mp4') ? 'video/mp4'
                    : full.endsWith('.html') ? 'text/html; charset=utf-8'
                    : 'application/octet-stream',
      'Content-Length': st.size,
    });
    fs.createReadStream(full).pipe(res);
  });
}).listen(PORT, '127.0.0.1', () => console.log('serving ' + ROOT + ' on ' + PORT));
