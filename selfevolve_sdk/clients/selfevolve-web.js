/**
 * Self-Evolve Engine — 컴퓨터/웹용 클라이언트 (브라우저 JS), 도메인 독립.
 * PC 프로그램(Electron)·웹앱에서 익명 이벤트를 배치로 전송. 개인정보 미수집.
 *
 * 사용법:
 *   const se = SelfEvolve({ baseUrl: 'https://server/se', domain: 'stock' });
 *   se.log('signal_view', { context: 'youtuber_A' });
 *   se.log('order_try', { context: 'buy', ok: false });   // 실패 = 숨은 니즈 신호
 *   se.setEnabled(false);                                  // 옵트아웃
 *   window.addEventListener('beforeunload', () => se.flush());
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.SelfEvolve = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  return function SelfEvolve(cfg) {
    const baseUrl = cfg.baseUrl, domain = cfg.domain, flushEvery = cfg.flushEvery || 10;
    const store = (typeof localStorage !== 'undefined') ? localStorage : { getItem: () => null, setItem: () => {} };
    let anonId = store.getItem('se_anon_id');
    if (!anonId) { anonId = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random()); store.setItem('se_anon_id', anonId); }
    let queue = [];
    const enabled = () => store.getItem('se_enabled') !== '0';

    function send(events) {
      if (!enabled() || !events.length) return;
      const body = JSON.stringify({ domain, anon_id: anonId, events });
      // sendBeacon 우선(종료 시에도 안전), 없으면 fetch
      if (navigator.sendBeacon) navigator.sendBeacon(baseUrl + '/events', new Blob([body], { type: 'application/json' }));
      else fetch(baseUrl + '/events', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body, keepalive: true }).catch(() => {});
    }
    return {
      setEnabled: (on) => { store.setItem('se_enabled', on ? '1' : '0'); if (!on) queue = []; },
      isEnabled: enabled,
      log(event, o) {
        o = o || {};
        if (!enabled()) return;
        queue.push({ event: String(event).slice(0, 64), context: String(o.context || '').slice(0, 96), ok: o.ok !== false, meta: String(o.meta || '').slice(0, 240) });
        if (queue.length >= flushEvery) { const r = queue; queue = []; send(r); }
      },
      flush() { if (queue.length) { const r = queue; queue = []; send(r); } }
    };
  };
});
