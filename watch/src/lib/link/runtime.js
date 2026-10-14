// Application-owned link runtime for the watch.
//
// The runtime is created once per application (see src/app.ux) and shared by every page through
// `$app.$def.getLinkRuntime()`. That single ownership is what makes the connection persistent:
// navigating between pages, hiding the app or leaving the dashboard never tears the GATT server
// down, so the phone's reconnect loop always has something to connect to.
//
// Responsibilities, in order:
//   1. load the day archive and the connection identity once;
//   2. start the link server and keep it alive through the watchdog in ./server-core.mjs;
//   3. expose a small, page-friendly state object to the UI.

import storage from '@blueos.storage.storage'
import { createArchive } from '../archive.mjs'
import { createIdentity, IDENTITY_KEY } from './identity.mjs'
import { createLinkServer } from './server.js'

function io(method, options) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('连接信息保存超时')), 10000)
    try {
      storage[method](Object.assign({}, options, {
        success: result => { clearTimeout(timer); resolve(result) },
        fail: () => { clearTimeout(timer); reject(new Error('连接信息保存失败')) }
      }))
    } catch (_) { clearTimeout(timer); reject(new Error('连接存储不可用')) }
  })
}

export function createRuntime() {
  const archive = createArchive(storage)
  const identity = createIdentity({
    async load() { return (await io('get', { key: IDENTITY_KEY, default: '' })) || '' },
    async save(text) { await io('set', { key: IDENTITY_KEY, value: text }) }
  })

  const listeners = []
  const state = { message: '正在启动直连服务…', connected: false, busy: false, pending: false, paused: false,
    saved: 0, ready: false, serverReady: false, advertising: false, peers: [], error: '', nativeStage: 'loading',
    reads: 0, writes: 0 }
  let server = null, starting = null, ended = false, saving = false

  function publish(update) {
    if (update) Object.assign(state, update)
    state.pending = identity.pending
    const snapshot = Object.assign({}, state)
    listeners.slice().forEach(listener => { try { listener(Object.assign({}, snapshot)) } catch (_) {} })
  }

  function openServer() {
    server = createLinkServer(identity, archive, {
      state: value => publish({ message: value.message, connected: value.connected, busy: value.busy,
        paused: value.paused, serverReady: value.serverReady, advertising: value.advertising, peers: value.peers,
        error: value.error, nativeStage: value.nativeStage, reads: value.reads, writes: value.writes }),
      // The counter, not the day list, is what tells the dashboard to reload the visible day.
      saved: () => publish({ saved: state.saved + 1 })
    })
    return server
  }

  const runtime = {
    archive,
    start() {
      if (starting) return starting
      ended = false
      let loading = '读取日期记录'
      starting = (async () => {
        await archive.load()
        loading = '读取连接信息'
        try { await identity.load() }
        catch (_) {
          // A corrupt record must never brick the link: drop it so the phone can enrol again.
          await identity.forget()
          publish({ message: '旧连接信息已重置，请在手机重新连接' })
        }
        if (ended) return
        publish({ ready: true })
        if (!server) openServer()
        server.start()
        publish()
      })().catch(() => publish({ message: loading + '失败，请重新打开应用', ready: false, serverReady: false }))
      return starting
    },
    /** Cheap idempotent check used by the page when it becomes visible again. */
    async ensureServer() {
      await this.start()
      if (ended || !server) return
      server.ensureServer()
    },
    subscribe(listener) {
      listeners.push(listener)
      listener(Object.assign({}, state))
      return () => { const index = listeners.indexOf(listener); if (index >= 0) listeners.splice(index, 1) }
    },
    async requestUpdate() {
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
    async clearPhone() {
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
    close() {
      ended = true
      if (server) server.close()
      server = null; starting = null
      publish({ connected: false, busy: false, ready: false, serverReady: false, advertised: false, message: '直连服务已关闭' })
    }
  }
  return runtime
}

const runtime = createRuntime()
export default runtime
