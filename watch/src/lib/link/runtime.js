// Application-owned link runtime for the watch.
//
// The runtime is created once per application (see src/app.ux) and shared by every page through
// `$app.$def.getLinkRuntime()`. That single ownership is what makes the connection persistent:
// navigating between pages, hiding the app or leaving the dashboard never tears the GATT server
// down, so the phone's reconnect loop always has something to connect to.
//
// Two rules this file follows, both learned the hard way on real hardware:
//
//   1. The stages are independent. Reading the day archive or the stored connection record must
//      never be able to prevent the GATT server from starting: without the server there is no
//      advertising at all, and the phone can then only sit in a connect timeout forever.
//   2. Every failure carries its own text to the dashboard. A message like "读取连接信息失败" is
//      useless for diagnosis, so the underlying error is included verbatim.

import storage from '@blueos.storage.storage'
import { createArchive } from '../archive.mjs'
import { createIdentity, IDENTITY_KEY } from './identity.mjs'
import { createLinkServer } from './server.js'

/** Error text that is safe to show on the watch face: never includes key material. */
function describe(error) {
  if (error && typeof error.message === 'string' && error.message) return error.message
  return String(error)
}

/**
 * Which runtime capabilities this link depends on are absent.
 *
 * The BlueOS app runtime is a polyfilled engine, and a missing global is invisible until the exact
 * line that uses it runs — which is how "reading the connection record failed" ended up masking a
 * GATT server that was never created. `typeof` never throws on an undeclared identifier, so this is
 * safe to evaluate before anything else, and the answer is appended to a start failure so one
 * install of the watch app is enough to identify the cause.
 */
function missingCapabilities() {
  const missing = []
  const required = {
    Promise: function () { return typeof Promise !== 'undefined' },
    setTimeout: function () { return typeof setTimeout !== 'undefined' },
    clearTimeout: function () { return typeof clearTimeout !== 'undefined' },
    DataView: function () { return typeof DataView !== 'undefined' },
    Uint8Array: function () { return typeof Uint8Array !== 'undefined' },
    'Object.assign': function () { return typeof Object.assign === 'function' },
    'Number.isSafeInteger': function () { return typeof Number.isSafeInteger === 'function' }
  }
  Object.keys(required).forEach(function (name) { if (!required[name]()) missing.push(name) })
  return missing
}

function io(method, options) {
  return new Promise(function (resolve, reject) {
    const timer = setTimeout(function () { reject(new Error('连接信息保存超时')) }, 10000)
    try {
      storage[method](Object.assign({}, options, {
        success: function (result) { clearTimeout(timer); resolve(result) },
        fail: function () { clearTimeout(timer); reject(new Error('连接信息保存失败')) }
      }))
    } catch (_) { clearTimeout(timer); reject(new Error('连接存储不可用')) }
  })
}

export function createRuntime() {
  const archive = createArchive(storage)
  const identity = createIdentity({
    load: function () { return io('get', { key: IDENTITY_KEY, default: '' }).then(function (value) { return value || '' }) },
    save: function (text) { return io('set', { key: IDENTITY_KEY, value: text }) }
  })

  const listeners = []
  const state = { message: '正在启动直连服务…', connected: false, busy: false, pending: false, paused: false,
    saved: 0, ready: false, serverReady: false, advertising: false, peers: [], error: '', nativeStage: 'loading',
    reads: 0, writes: 0 }
  let server = null, starting = null, ended = false, saving = false
  const missing = missingCapabilities()

  function publish(update) {
    if (update) Object.assign(state, update)
    state.pending = identity.pending
    const snapshot = Object.assign({}, state)
    listeners.slice().forEach(function (listener) {
      try { listener(Object.assign({}, snapshot)) } catch (_) {}
    })
  }

  function openServer() {
    server = createLinkServer(identity, archive, {
      state: function (value) {
        // Only decorate a start failure: everywhere else the server's own message is the answer.
        let text = value.message
        if (missing.length && typeof text === 'string' && text.indexOf('启动失败') >= 0) {
          text = text + '（缺少：' + missing.join('、') + '）'
        }
        publish({ message: text, connected: value.connected, busy: value.busy,
          paused: value.paused, serverReady: value.serverReady, advertising: value.advertising, peers: value.peers,
          error: value.error, nativeStage: value.nativeStage, reads: value.reads, writes: value.writes })
      },
      // The counter, not the day list, is what tells the dashboard to reload the visible day.
      saved: function () { publish({ saved: state.saved + 1 }) }
    })
    return server
  }

  const runtime = {
    archive: archive,
    start: function () {
      if (starting) return starting
      ended = false
      starting = (async function () {
        // Stage 1: the day archive. A damaged cache must not stop the link from starting.
        try { await archive.load() }
        catch (error) { publish({ error: describe(error), message: '日期缓存不可读，仍可接收手机更新' }) }
        // Stage 2: the stored connection record. A rejected record is discarded, not fatal.
        try { await identity.load() }
        catch (error) {
          let cleared = true
          try { await identity.forget() } catch (failure) { cleared = false; publish({ error: describe(failure) }) }
          publish({ message: cleared ? '旧连接信息已重置，请在手机重新连接'
            : '连接信息不可读（' + describe(error) + '），请重新打开应用' })
        }
        if (ended) return
        publish({ ready: true })
        // Stage 3: the native GATT server. Its failure is reported with its own text so the watch
        // face says exactly what went wrong instead of a generic retry prompt.
        try {
          if (!server) openServer()
          server.start()
        } catch (error) { publish({ message: '直连服务启动失败 · ' + describe(error) }) }
        publish()
      })()
      return starting
    },
    /** Cheap idempotent check used by the page when it becomes visible again. */
    ensureServer: async function () {
      await this.start()
      if (ended || !server) return
      server.ensureServer()
    },
    subscribe: function (listener) {
      listeners.push(listener)
      listener(Object.assign({}, state))
      return function () { const index = listeners.indexOf(listener); if (index >= 0) listeners.splice(index, 1) }
    },
    requestUpdate: async function () {
      await this.start()
      if (!state.ready) throw new Error('直连服务未准备好，请重新打开应用')
      if (!state.serverReady || !server) throw new Error(state.message || '直连服务未开启')
      if (state.busy || saving) return
      saving = true
      try {
        await identity.queueRequest()
        publish({ pending: true })
        server.request()
      } finally { saving = false }
    },
    clearPhone: async function () {
      await this.start()
      if (!state.ready) throw new Error('直连服务未准备好，请重新打开应用')
      if (state.busy || saving) throw new Error('请等待当前更新完成')
      if (!server || !server.pauseEnrolment()) throw new Error('连接正在处理，请稍后再操作')
      saving = true
      try {
        await identity.clearSecret()
        publish({ message: '旧手机连接已清除，请在手机搜索并连接', connected: false })
      } finally {
        saving = false
        if (server) server.resumeEnrolment()
      }
    },
    close: function () {
      ended = true
      if (server) server.close()
      server = null; starting = null
      publish({ connected: false, busy: false, ready: false, serverReady: false, advertising: false, message: '直连服务已关闭' })
    }
  }
  return runtime
}

const runtime = createRuntime()
export default runtime
