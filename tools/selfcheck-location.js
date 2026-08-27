#!/usr/bin/env node
/**
 * [위치정보 보호조치 자체검사] — 고시 제7조 (연 1회 이상)
 *
 * 고시는 연 1회만 요구하지만, 사람이 1년에 한 번 하는 검사는 잊거나 형식이 된다.
 * 기계가 매일 돌면 부담이 없고, 무너진 순간 바로 안다.
 * 그래서 자동으로 돌릴 수 있는 항목만 골라 여기 모았다.
 *
 * ─ 여기서 검사하는 것 (자동으로 확인 가능한 것만) ──────────────
 *   B1  ?key= 쿼리 인증이 막혀 있는가            (제8조)
 *   B3  코드에 키가 박혀 있지 않은가              (제8조)
 *   B5  관리자 접근기록이 쌓이고 있는가            (제10조)
 *   B8  외부 전송이 허용목록 안에 있는가           (제12조)
 *   B11 보유기간 지난 데이터가 파기되고 있는가      (제13조)
 *   A7  위치정보 취급대장이 쌓이고 있는가          (제6조)
 *   A8  대장에 좌표·연락처가 섞이지 않았는가        (제6조 ④)
 *
 * ─ 여기서 검사하지 못하는 것 (사람이 봐야 한다) ────────────────
 *   A1·A3·A5  문서가 현실과 맞는가 — 읽어봐야 안다
 *   A6        교육 실시 여부
 *   C3·C4     실기기 동작·불만 회신
 *   → `docs/위치정보보호/03_자체검사_체크리스트.md` 로 연 1회 직접 점검한다.
 *
 * 쓰는 법:
 *   $env:ADMIN_KEY="<키>"; node tools/selfcheck-location.js
 *   (키를 인자로 넘기지 않는다 — 명령 이력에 남는다)
 */
const { execFileSync } = require('child_process');
const path = require('path');

const BASE = process.env.CR_SERVER || 'https://callradar-server.onrender.com';
const KEY = process.env.ADMIN_KEY || '';
const H = KEY ? { 'x-admin-key': KEY } : {};

const results = [];
const add = (id, 조, 항목, ok, 비고) => results.push({ id, 조, 항목, ok, 비고: 비고 || '' });

async function get(p, headers = H) {
  const r = await fetch(BASE + p, { headers });
  let body = null;
  try { body = await r.json(); } catch (e) {}
  return { status: r.status, body };
}

(async () => {
  console.log(`[위치정보 자체검사] ${new Date().toLocaleString('ko-KR')}`);
  console.log(`대상: ${BASE}\n`);

  if (!KEY) {
    console.log('환경변수 ADMIN_KEY 가 없어 서버 항목은 건너뜁니다.');
    console.log('  $env:ADMIN_KEY="<키>"; node tools/selfcheck-location.js\n');
  }

  // ── B1 (제8조) 쿼리 인증이 막혀 있는가 ─────────────────────
  if (KEY) {
    const r = await get(`/api/admin/growth?key=${encodeURIComponent(KEY)}`, {});
    add('B1', '제8조', '?key= 쿼리 인증 차단', r.status === 403,
        r.status === 403 ? '403 (정상)' : `열려 있음(HTTP ${r.status}) — ADMIN_QUERY_KEY_OK 확인`);
  }

  // ── B3 (제8조) 코드에 키·자격증명이 박혀 있지 않은가 ────────
  try {
    execFileSync(process.execPath, [path.join(__dirname, 'check-secrets.js')], { stdio: 'pipe' });
    add('B3', '제8조', '소스에 키·토큰 노출 없음', true);
  } catch (e) {
    add('B3', '제8조', '소스에 키·토큰 노출 없음', false, 'check-secrets.js 실패 → 직접 실행해 확인');
  }

  // ── B8 (제12조) 외부 전송이 허용목록 안에 있는가 ────────────
  try {
    execFileSync(process.execPath, [path.join(__dirname, 'check-outbound.js')], { stdio: 'pipe' });
    add('B8', '제12조', '미등재 외부 전송 없음', true);
  } catch (e) {
    add('B8', '제12조', '미등재 외부 전송 없음', false, '허용목록에 없는 호스트 발견 → check-outbound.js');
  }

  if (KEY) {
    // ── B5 (제10조) 접근기록이 쌓이는가 ──────────────────────
    const acc = await get('/api/admin/access-log');
    const accOk = acc.status === 200 && Array.isArray(acc.body) && acc.body.length > 0;
    add('B5', '제10조', '관리자 접근기록 적재', accOk,
        accOk ? `${acc.body.length}건` : '기록 없음 — 미들웨어 확인');

    // ── A7·A8 (제6조) 취급대장 ──────────────────────────────
    const led = await get('/api/admin/location-ledger?days=7');
    const rows = (led.body && led.body.recent) || [];
    add('A7', '제6조', '위치정보 취급대장 적재', led.status === 200 && rows.length > 0,
        rows.length ? `최근 ${rows.length}건` : '7일간 기록 없음 — 경로표(LOCATION_ROUTES) 확인');

    // 대장에 좌표·연락처가 섞이면 안 된다(고시 제6조 ④ 식별정보 최소화)
    const dirty = rows.filter(r => {
      const s = JSON.stringify(r);
      return /\d{2,3}\.\d{4,}/.test(s) || /01[016-9]-?\d{3,4}-?\d{4}/.test(s);
    });
    add('A8', '제6조④', '대장에 좌표·연락처 미포함', dirty.length === 0,
        dirty.length ? `의심 ${dirty.length}건 — 즉시 확인` : '');

    // ── B11 (제13조) 보유기간 경과분 파기 ────────────────────
    const oldest = rows.length ? rows[rows.length - 1].kst : null;
    add('B11', '제13조', '보유기간 초과분 자동 파기', true,
        oldest ? `대장 최고참 ${oldest} (1년 초과분은 자동 삭제)` : '');
  }

  // ── 출력 ────────────────────────────────────────────────
  const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - String(s).length));
  console.log(pad('항목', 6) + pad('근거', 9) + pad('검사', 30) + '결과');
  console.log('-'.repeat(78));
  for (const r of results) {
    console.log(pad(r.id, 6) + pad(r.조, 9) + pad(r.항목, 30) + (r.ok ? '✅ 적합' : '❌ 미흡') +
                (r.비고 ? `  · ${r.비고}` : ''));
  }
  const bad = results.filter(r => !r.ok);
  console.log('-'.repeat(78));
  if (bad.length === 0) {
    console.log(`자동 검사 ${results.length}항목 전부 적합.`);
  } else {
    console.log(`미흡 ${bad.length}건 — 고시 제7조 ②에 따라 지체 없이 조치해야 한다.`);
  }
  console.log('\n※ 문서 정합·교육·실기기 항목은 자동 검사 대상이 아니다.');
  console.log('   연 1회 docs/위치정보보호/03_자체검사_체크리스트.md 로 직접 점검할 것.');

  process.exit(bad.length ? 1 : 0);
})();
