/**
 * Self-Evolve Engine — 도메인 독립 자기진화 니즈발굴 엔진 (서버/Node·Express)
 *
 * 무엇: 어떤 앱/프로그램이든 익명 사용·행동·결과 이벤트를 수집 → 집계 →
 *       "실패·이탈·반복시도 = 유저가 말하지 않은 니즈"를 발굴해 내부 리포트로 돌려준다.
 *       (콜레이더에서 검증된 루프를 도메인 독립으로 일반화.)
 *
 * 개인정보: 절대 수집하지 않는다. anon_id(무작위)·domain·event·context·성공여부·수치 meta만.
 *
 * 사용법:
 *   const { Pool } = require('pg');
 *   const pool = new Pool({ connectionString: process.env.DATABASE_URL });
 *   const selfEvolve = require('./selfevolve')({ pool });      // 옵션: { tableName }
 *   app.use('/se', selfEvolve.router);                         // POST /se/events, GET /se/report
 *   // 또는 프로그램 내부에서 직접:
 *   await selfEvolve.log({ domain:'stock', anonId, event:'signal_view', context:'youtuber_A', ok:true });
 *   const report = await selfEvolve.report({ domain:'stock', days:7 });
 */
const express = require('express');

module.exports = function createSelfEvolve(opts = {}) {
  const pool = opts.pool;
  if (!pool) throw new Error('selfevolve: pool(pg) required');
  const T = (opts.tableName || 'se_events').replace(/[^a-zA-Z0-9_]/g, '');
  const s = (v, n) => String(v == null ? '' : v).slice(0, n);

  // 스키마 (도메인 컬럼으로 여러 종목이 1DB 공유)
  pool.query(`CREATE TABLE IF NOT EXISTS ${T} (
    id SERIAL PRIMARY KEY,
    domain TEXT NOT NULL,
    anon_id TEXT,
    event TEXT NOT NULL,
    context TEXT,
    ok BOOLEAN DEFAULT true,
    meta TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
  )`).catch(() => {});
  pool.query(`CREATE INDEX IF NOT EXISTS idx_${T}_dc ON ${T}(domain, created_at)`).catch(() => {});

  async function log(e = {}) {
    if (!e.event || !e.domain) throw new Error('domain, event required');
    await pool.query(
      `INSERT INTO ${T} (domain, anon_id, event, context, ok, meta) VALUES ($1,$2,$3,$4,$5,$6)`,
      [s(e.domain, 48), s(e.anonId || e.anon_id, 64), s(e.event, 64), s(e.context, 96), e.ok !== false, s(e.meta, 240)]
    );
  }

  async function report({ domain, days = 7 } = {}) {
    if (!domain) throw new Error('domain required');
    const d = Math.min(parseInt(days) || 7, 365);
    const W = `domain=$1 AND created_at > NOW() - INTERVAL '${d} days'`;
    const q = async (sql) => (await pool.query(sql, [domain])).rows;
    const one = async (sql) => (await pool.query(sql, [domain])).rows[0] || {};
    const activeUsers = (await one(`SELECT COUNT(DISTINCT anon_id)::int c FROM ${T} WHERE ${W} AND anon_id<>''`)).c || 0;
    const totalEvents = (await one(`SELECT COUNT(*)::int c FROM ${T} WHERE ${W}`)).c || 0;
    const topEvents = await q(`SELECT event, COUNT(*)::int c FROM ${T} WHERE ${W} GROUP BY event ORDER BY c DESC LIMIT 15`);
    const topContexts = await q(`SELECT context, COUNT(*)::int c FROM ${T} WHERE ${W} AND context<>'' GROUP BY context ORDER BY c DESC LIMIT 15`);
    // 핵심: 미발화 니즈 = 실패(ok=false) 많은 (event,context) 조합
    const hiddenNeeds = await q(
      `SELECT event, context, COUNT(*)::int fails,
              ROUND(100.0*COUNT(*)/NULLIF((SELECT COUNT(*) FROM ${T} b WHERE b.domain=$1 AND b.event=${T}.event),0))::int fail_pct
       FROM ${T} WHERE ${W} AND ok=false GROUP BY event, context ORDER BY fails DESC LIMIT 15`
    );
    return {
      domain, period: d + 'd', generatedAt: new Date().toISOString(),
      activeUsers, totalEvents, topEvents, topContexts, hiddenNeeds,
      note: '실패·이탈(ok=false) 많은 항목 = 유저가 원하나 안 되는 것(숨은 니즈) → 개발 우선순위'
    };
  }

  const router = express.Router();
  router.use(express.json());
  router.post('/events', async (req, res) => {
    try {
      const body = req.body || {};
      const items = Array.isArray(body.events) ? body.events : [body]; // 단건 또는 배치
      for (const e of items) await log({ domain: body.domain || e.domain, anonId: body.anon_id || e.anon_id || e.anonId, ...e });
      res.json({ ok: true, count: items.length });
    } catch (e) { res.status(400).json({ error: e.message }); }
  });
  router.get('/report', async (req, res) => {
    try { res.json(await report({ domain: req.query.domain, days: req.query.days })); }
    catch (e) { res.status(400).json({ error: e.message }); }
  });

  return { router, log, report };
};
