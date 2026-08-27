#!/usr/bin/env node
/**
 * [시크릿·토큰 유출 검사] 코드에 키가 박혀 있거나, 자사 토큰이 남의 서버로 나가는지 본다.
 *
 * 왜 만들었나 (2026-08-26):
 *   ① 공항 번역 기능이 api.mymemory.translated.net 에 요청하면서 자사 세션 Bearer 토큰을
 *      그대로 붙여 보내고 있었다. 자사 서버 호출 코드를 복사하면서 딸려온 것이다.
 *      다른 제3자 호출(Cloudinary·Nominatim)엔 토큰을 빼는 방어가 있었는데 거기만 빠졌다.
 *   ② server/tolls.js 에 EX_API_KEY 가 하드코딩 폴백으로 남아 있었다.
 *      정관 7-1 "API 키는 Render 환경변수(하드코딩·git 금지)" 위반이 코드에 앉아 있었다.
 *
 *   둘 다 사람 눈으로는 몇 주째 안 보였다. 기계가 매번 보게 한다.
 *
 * 쓰는 법:  node tools/check-secrets.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, 'outbound-allowlist.json'), 'utf8'));

/** 자사 호스트 — 여기로 가는 요청에만 우리 토큰을 붙여도 된다. */
const OWN_HOSTS = ['callradar-server.onrender.com', 'localhost'];

const SCAN = [
  { dir: path.join(ROOT, 'app', 'src', 'main', 'java'), ext: ['.kt', '.java'] },
  { dir: path.join(ROOT, 'server'), ext: ['.js'], skip: ['node_modules', 'public'] },
];

function walk(dir, ext, skip, out) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return out; }
  for (const e of entries) {
    if (skip && skip.includes(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, ext, skip, out);
    else if (ext.some(x => e.name.endsWith(x))) out.push(p);
  }
  return out;
}

const files = SCAN.flatMap(s => walk(s.dir, s.ext, s.skip, []));
const problems = [];

/**
 * 이 문자열이 '진짜 비밀값'처럼 보이는가.
 *  SharedPreferences 키 이름("work_segments_v1", "onboarding_done")과
 *  실제 키("90d96600658fbf204a3032a69455e8b8")를 갈라야 한다.
 *  기준: 밑줄로 이어진 소문자 단어들은 이름이다. 비밀값은 보통 엔트로피가 높다.
 */
function looksSecret(v) {
  if (v.length < 16) return false;
  if (/^[a-z][a-z0-9]*(_[a-z0-9]+)+$/.test(v)) return false;   // snake_case 이름
  if (/^[a-z]+$/.test(v)) return false;                        // 소문자 단어 하나
  const hex = /^[0-9a-f]{24,}$/i.test(v);
  const b64 = /^[A-Za-z0-9+/=_-]{28,}$/.test(v);
  const mixed = /[a-z]/.test(v) && /[A-Z]/.test(v) && /[0-9]/.test(v) && v.length >= 20;
  return hex || b64 || mixed;
}

// ── 검사 1: 자사 토큰이 외부 호스트 요청에 붙는가 ─────────────────
// 한 줄 안에 '외부 URL'과 'Authorization/Auth.tok'이 같이 있으면 의심.
// (우리 코드가 URL 열기와 헤더 설정을 한 줄에 몰아 쓰는 스타일이라 이 방식이 잘 잡힌다.)
// ★ '우리 자격증명'만 본다.
//   그냥 Authorization/Bearer 를 다 잡으면 카카오에 카카오 키를 보내는 정상 호출까지 걸려서
//   경고가 쌓이고, 쌓이면 사람이 무시한다. 무시당하는 검사기는 없는 것과 같다.
//   실제 사고는 "자사 서버 호출 코드를 복사해 외부 API에 붙인 것"이었으므로
//   자사 자격증명 이름만 정확히 추적한다.
const AUTH_HINT = /(Auth\.tok|x-admin-key|ADMIN_KEY|SESSION_SECRET|JWT_SECRET)/;
const URL_IN_LINE = /https?:\/\/([A-Za-z0-9._-]+)/g;

for (const file of files) {
  const rel = path.relative(ROOT, file);
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  lines.forEach((text, i) => {
    if (text.trim().startsWith('//') || text.trim().startsWith('*')) return;   // 주석은 건너뜀
    if (!AUTH_HINT.test(text)) return;
    URL_IN_LINE.lastIndex = 0;
    let m;
    while ((m = URL_IN_LINE.exec(text)) !== null) {
      const host = m[1].toLowerCase();
      if (host.includes('$') || !host.includes('.')) continue;      // 변수 보간
      if (OWN_HOSTS.includes(host)) continue;                        // 자사 서버는 정상
      if (host === 'api.anthropic.com') continue;                    // 서버가 자기 API키로 부르는 것(정상)
      problems.push({
        kind: '토큰유출',
        msg: `외부 호스트(${host}) 요청에 인증정보가 붙어 있을 수 있다`,
        where: `${rel}:${i + 1}`,
      });
    }
  });
}

// ── 검사 2: 소스에 박힌 키 ────────────────────────────────────────
// process.env.X || '실제값'  패턴 — 환경변수가 없을 때 쓰라고 넣어둔 진짜 키.
const ENV_FALLBACK = /process\.env\.([A-Z0-9_]*(KEY|SECRET|TOKEN|PASSWORD|PW)[A-Z0-9_]*)\s*\|\|\s*['"]([^'"]+)['"]/g;

// [2026-08-27 보강] 접속 문자열에 박힌 자격증명.
//  이 검사기를 만들고도 server/_an*.js 20여 개에 DB 비밀번호가 통째로 박힌 걸 못 잡았다.
//  기존 규칙이 'process.env.X || 값' 과 코틀린 상수만 보고 있었기 때문이다.
//  검사기를 만들었다고 안심하면 안 된다 — 못 보는 영역을 계속 넓혀야 한다.
const CONN_STRING = /\b(postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis|amqp):\/\/[^:\s'"]+:([^@\s'"]{4,})@/gi;
// 안드로이드 쪽: KEY/SECRET/TOKEN 이름의 상수에 긴 리터럴이 박힌 경우
const KT_CONST = /(?:const\s+val|val|var)\s+([A-Za-z_]*(?:KEY|SECRET|TOKEN|PASSWORD)[A-Za-z_]*)\s*(?::\s*String\s*)?=\s*"([^"]{12,})"/g;

for (const file of files) {
  const rel = path.relative(ROOT, file);
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  lines.forEach((text, i) => {
    if (text.trim().startsWith('//')) return;
    let m;
    ENV_FALLBACK.lastIndex = 0;
    while ((m = ENV_FALLBACK.exec(text)) !== null) {
      if (m[3].length < 6) continue;                       // '' 나 '0.4' 같은 기본값은 키가 아님
      if (/^(true|false|\d+(\.\d+)?)$/.test(m[3])) continue;
      problems.push({
        kind: '키하드코딩',
        msg: `${m[1]} 에 소스 폴백값이 박혀 있다 — 정관 7-1 위반 (키는 환경변수만)`,
        where: `${rel}:${i + 1}`,
      });
    }
    CONN_STRING.lastIndex = 0;
    while ((m = CONN_STRING.exec(text)) !== null) {
      // `postgresql://${process.env.DB_USER}:${process.env.DB_PASSWORD}@...` 처럼
      // 환경변수를 끼워 넣는 건 정상이다. 실제 값이 박힌 것만 잡는다.
      if (m[2].includes('${') || m[2].includes('process.env')) continue;
      problems.push({
        kind: '접속문자열',
        msg: `${m[1]} 접속 문자열에 비밀번호가 박혀 있다 — 환경변수로 빼야 한다`,
        where: `${rel}:${i + 1}`,
      });
    }
    KT_CONST.lastIndex = 0;
    while ((m = KT_CONST.exec(text)) !== null) {
      if (/^(https?:|\/|[가-힣])/.test(m[2])) continue;     // URL·경로·한글 라벨은 키가 아님
      if (!looksSecret(m[2])) continue;                     // SharedPreferences 키 이름 등은 제외
      problems.push({
        kind: '키하드코딩',
        msg: `${m[1]} 에 값이 박혀 있다 — 키라면 빼야 한다`,
        where: `${rel}:${i + 1}`,
      });
    }
  });
}

// ── 검사 3: 방침 근거 없는 호스트가 아직 남아 있는가 (요약만) ──────
const noPolicy = cfg.allow.filter(a => !a.policy);

console.log('[시크릿·토큰 검사]\n');
if (!problems.length) {
  console.log('  발견된 문제 없음');
} else {
  const byKind = {};
  for (const p of problems) (byKind[p.kind] ||= []).push(p);
  for (const kind of Object.keys(byKind)) {
    console.log(`── ${kind} (${byKind[kind].length}건) ──`);
    for (const p of byKind[kind]) console.log(`  ! ${p.where}\n      ${p.msg}`);
    console.log('');
  }
}

if (noPolicy.length) {
  console.log(`── 참고: 방침 근거 없는 외부 호스트 ${noPolicy.length}건 ──`);
  for (const a of noPolicy) console.log(`  · ${a.host} (${a.수탁자})`);
  console.log('  → 개인정보처리방침·약관 정비 필요. 자세한 내용은 check-outbound.js');
  console.log('');
}

if (problems.length) { console.log('결과: 실패'); process.exit(1); }
console.log('결과: 통과');
