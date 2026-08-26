#!/usr/bin/env node
/**
 * [외부전송 게이트] 코드에 새 외부 호스트가 들어왔는지 검사한다.
 *
 * 왜 만들었나 (2026-08-26):
 *   영수증 사진이 Cloudinary(미국)에 올라가고 그 URL을 Anthropic(미국)이 가져가 판독하고 있었는데,
 *   개인정보처리방침 제5조 수탁자 표에는 Render·카카오 둘뿐이었고
 *   제4조에는 "제3자에게 제공하지 않습니다"라고 적혀 있었다.
 *   코드와 문서가 어긋난 채로 두 스토어에 배포되어 있었다.
 *
 *   "다음부터 조심하겠다"는 또 잊는다. 기계가 잡게 만든다.
 *
 * 무엇을 하나:
 *   1. 앱(.kt)·서버(.js) 코드에서 http(s) 호스트를 전부 긁는다.
 *   2. tools/outbound-allowlist.json 에 없는 호스트가 있으면 → 실패(exit 1).
 *   3. 등재돼 있어도 policy 가 null 이면 → 경고로 보여준다(방침에 근거가 없다는 뜻).
 *
 * 쓰는 법:
 *   node tools/check-outbound.js          경고까지 다 보되, 미등재만 실패
 *   node tools/check-outbound.js --strict  방침 근거 없는 것도 실패 (배포 전 검사용)
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const STRICT = process.argv.includes('--strict');

const SCAN = [
  { dir: path.join(ROOT, 'app', 'src', 'main', 'java'), ext: ['.kt', '.java'] },
  { dir: path.join(ROOT, 'server'), ext: ['.js'], skip: ['node_modules', 'public'] },
];

const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, 'outbound-allowlist.json'), 'utf8'));
const allow = new Map(cfg.allow.map(a => [a.host.toLowerCase(), a]));
const linkOnly = new Set((cfg.link_only || []).map(h => h.toLowerCase()));

/** 소스에서 http(s) 호스트를 뽑는다. 주석 안이어도 잡는다(놓치는 것보다 낫다). */
const HOST_RE = /https?:\/\/([A-Za-z0-9._-]+)/g;

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

const found = new Map();   // host -> [{file, line}]
for (const s of SCAN) {
  for (const file of walk(s.dir, s.ext, s.skip, [])) {
    const lines = fs.readFileSync(file, 'utf8').split('\n');
    lines.forEach((text, i) => {
      let m;
      HOST_RE.lastIndex = 0;
      while ((m = HOST_RE.exec(text)) !== null) {
        const host = m[1].toLowerCase().replace(/[.]+$/, '');
        // 코드 안의 변수 보간(${...})이나 예시 도메인은 건너뛴다
        if (!host.includes('.') || host.includes('$')) continue;
        if (host === 'example.com' || host === 'localhost') continue;
        if (!found.has(host)) found.set(host, []);
        const hits = found.get(host);
        if (hits.length < 4) hits.push({ file: path.relative(ROOT, file), line: i + 1 });
      }
    });
  }
}

const unknown = [];
const noPolicy = [];
for (const [host, hits] of [...found].sort()) {
  if (linkOnly.has(host)) continue;
  const a = allow.get(host);
  if (!a) { unknown.push({ host, hits }); continue; }
  if (!a.policy) noPolicy.push({ host, a, hits });
}

console.log(`[외부전송 검사] 코드에서 발견한 호스트 ${found.size}개\n`);

if (noPolicy.length) {
  console.log('── 방침 근거 없음 (개인정보처리방침·약관에 적혀 있지 않다) ──');
  for (const { host, a, hits } of noPolicy) {
    console.log(`  ! ${host}  [${a.수탁자}]`);
    console.log(`      나가는 것: ${a.나가는것}`);
    if (a.note) console.log(`      메모: ${a.note}`);
    console.log(`      예: ${hits.map(h => `${h.file}:${h.line}`).join(', ')}`);
  }
  console.log('');
}

if (unknown.length) {
  console.log('── 미등재 호스트 (허용목록에 없다) ──');
  for (const { host, hits } of unknown) {
    console.log(`  X ${host}`);
    console.log(`      ${hits.map(h => `${h.file}:${h.line}`).join(', ')}`);
  }
  console.log('');
  console.log('새 외부 호출을 추가했다면 tools/outbound-allowlist.json 에 등재하고,');
  console.log('무엇이 나가는지와 방침 어느 조항에 근거가 있는지를 함께 적어라.');
  console.log('방침에 없다면 방침을 먼저 고친다. 코드가 문서를 앞서가면 안 된다.');
}

if (unknown.length) { console.log('\n결과: 실패 (미등재 호스트)'); process.exit(1); }
if (STRICT && noPolicy.length) { console.log('결과: 실패 (--strict: 방침 근거 없는 호스트)'); process.exit(1); }
console.log(`결과: 통과${noPolicy.length ? ` (경고 ${noPolicy.length}건 — 방침 정비 필요)` : ''}`);
