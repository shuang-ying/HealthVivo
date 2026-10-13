// Only runs while this extension is open. No cloud, file scraping, or health-record writes.
(function () {
  'use strict';
  const LINK_KEY = 'vitality.native-link.v1';
  function error(status, code, message) { const value = new Error(message); value.status = status; value.code = code; return value; }
  async function native(path, method, body) {
    const link = await ov.storage.get(LINK_KEY);
    if (!link || link.schema !== 1 || link.appId !== 'com.lrh.healthvivo.watch' ||
        link.baseUrl !== 'http://127.0.0.1:28341' || !/^[0-9a-f]{64}$/.test(link.token || '')) {
      throw error(409, 'PHONE_NOT_PAIRED', '请在扩展程序粘贴活力记录 App 的配对信息');
    }
    const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 7000);
    try {
      const response = await fetch(link.baseUrl + path, {
        method: method || 'GET', headers: { Authorization: 'Bearer ' + link.token, 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined, signal: controller.signal, cache: 'no-store',
      });
      if (!response.ok) throw error(response.status, response.status === 401 ? 'PHONE_AUTH_FAILED' : 'PHONE_RESPONSE_FAILED',
        response.status === 401 ? '配对信息已失效，请重新复制' : response.status === 404 && path === '/heartbeat' ? '请更新手机 App 至 0.12.3 或更高版本' : response.status === 409 ? '记录已有更新，请再次刷新' : '手机接口暂不可用，请重新开启持续连接');
      return await response.json();
    } catch (value) {
      if (value.status) throw value;
      throw error(503, 'PHONE_SESSION_CLOSED', '请在手机活力记录开启本机同步，再打开此扩展程序');
    } finally { clearTimeout(timeout); }
  }
  function route(method, path, handler) {
    ov.server.route(method, path, async function (request) {
      try { return ov.server.json(await handler(request), { headers: { 'cache-control': 'no-store' } }); }
      catch (value) { return ov.server.json({ error: { code: value.code || 'BRIDGE_ERROR', message: value.status ? value.message : '同步接口处理失败' } }, { status: value.status || 500 }); }
    });
  }
  async function watchInfo() {
    try {
      const info = await ov.watch.getInfo(); return { transportReady: info.connected === true && info.transportReady === true };
    } catch (_) { return { transportReady: false }; }
  }
  async function connectedControl(watchApp, version) {
    const info = await watchInfo();
    const control = await native('/heartbeat', 'POST', { transportReady: info.transportReady,
      watchAppReady: !!watchApp, watchVersion: version || '' });
    return Object.assign({}, control, { transportReady: info.transportReady });
  }
  async function requireTransport() {
    const info = await watchInfo();
    if (!info.transportReady) {
      // Clear stale readiness on the phone without fetching any health records.
      await native('/heartbeat', 'POST', { transportReady: false });
      throw error(503, 'WATCH_TRANSPORT_NOT_READY', '手表蓝牙通道未就绪，请在 OrbitV 连接 GT2');
    }
  }
  async function start() {
    const runtime = await ov.runtime.getInfo();
    if (runtime.appId !== 'com.lrh.healthvivo.watch') throw error(409, 'APP_ID_MISMATCH', '扩展程序包名不一致');
    route('GET', '/api/control', () => connectedControl(true));
    route('POST', '/api/control', async request => { const value = await request.json(); return connectedControl(true, value && value.version); });
    route('POST', '/api/request', async () => {
      await requireTransport(); const state = await connectedControl(false);
      if (!state.watchAppConnected) throw error(409, 'WATCH_APP_NOT_OPEN', '请打开手表活力记录，等待连接就绪');
      return native('/request', 'POST', {});
    });
    route('POST', '/api/sync', async request => {
      await requireTransport(); await connectedControl(true);
      return native('/sync', 'POST', await request.json());
    });
    route('POST', '/api/saved', async function (request) {
      const value = await request.json();
      if (!value || typeof value.receiptId !== 'string' || value.receiptId.length > 80 || !Array.isArray(value.days) || value.days.length > 12 ||
          value.days.some(day => !Number.isSafeInteger(day.revision) || !/^\d{4}-\d{2}-\d{2}$/.test(day.day))) throw error(400, 'INVALID_RECEIPT', '保存确认格式不正确');
      await requireTransport();
      const result = await native('/saved', 'POST', { receiptId: value.receiptId, days: value.days });
      return result;
    });
    await ov.server.ready();
  }
  (window.ov ? start() : new Promise(resolve => window.addEventListener('ovready', resolve, { once: true })).then(start))
    .catch(() => { (window.__orbitvServiceErrors || (window.__orbitvServiceErrors = [])).push('活力记录同步服务未能启动'); });
})();
