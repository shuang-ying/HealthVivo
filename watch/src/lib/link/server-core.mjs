// Watch-side BlueOS GATT server lifecycle.
//
// This module owns everything that touches the native BLE GATT server, and it exists mainly to
// make the link *persistent*, because the official API leaves two gaps that would otherwise
// silently strand the phone:
//
//   1. `startAdvertising` documents no return value, no success callback and no error code, so
//      a call can fail without telling us. The watchdog here re-requests advertising whenever we
//      believe nobody is connected, which makes a lost advertisement self-healing.
//   2. Nothing in the documentation guarantees that a GATT server instance survives a native
//      teardown. The watchdog therefore also rebuilds the whole server (service plus three
//      subscriptions) when the previous instance is no longer usable.
//
// Native modules and timers are injected so the Node test suite can drive a deterministic double
// instead of a real watch. The engine in ./protocol.mjs makes every protocol decision; this file
// only moves bytes and manages the native lifecycle.

import { SERVICE, CHARACTERISTIC, CHARACTERISTIC_PROPERTIES } from './frames.mjs'
import { createLinkProtocol } from './protocol.mjs'

/** Documented ranges: interval 32..16384 slots, txPower -127..1 dBm. */
export const ADVERTISE_SETTING = { interval: 1600, txPower: -7, connectable: true }
/** AdvertiseData requires serviceUuids or serviceData; the service UUID is what the phone filters on. */
export const ADVERTISE_DATA = { serviceUuids: [SERVICE] }
/** How often the watchdog re-checks the native server. */
export const HEALTH_INTERVAL = 3000
/** Re-request advertising when no phone has been connected for this long. */
export const READVERTISE_AFTER = 6000
/** Wait this long before rebuilding a server that failed to register. */
export const REOPEN_AFTER = 10000
/** Give the controller a moment after a disconnect before advertising again. */
export const ADVERTISE_DELAY = 400

const STAGE_LABEL = { create: '创建服务', 'add-service': '添加服务', 'subscribe-read': '监听读取',
  'subscribe-write': '监听写入', 'subscribe-connection': '监听连接', advertise: '请求广播' }
const peerId = value => (typeof value === 'string' ? value.trim().toLowerCase() : '')

/** BlueOS declares every binary field as ArrayBuffer; never hand it a view over a larger buffer. */
export function wire(value) {
  const view = value instanceof Uint8Array ? value : new Uint8Array(value || new ArrayBuffer(0))
  return view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength)
}

export function createLinkServerCore({ ble, cipher, identity, archive, hooks = {}, timers = globalThis, clock = () => Date.now() }) {
  const delay = timers.setTimeout.bind(timers), cancelDelay = timers.clearTimeout.bind(timers)
  const repeat = timers.setInterval.bind(timers), cancelRepeat = timers.clearInterval.bind(timers)
  let server = null, ready = false, stopped = true, nativeFailed = false
  let nativeStage = 'idle', lastError = '', message = '正在启动直连服务…'
  let connected = [], advertising = false
  let lastAdvertiseAt = 0, lastOpenAt = 0, health = null, advertiseTimer = null

  const protocol = createLinkProtocol({ cipher, identity, archive, clock, timers, hooks: { state: () => publish(), saved: hooks.saved } })

  function snapshot() {
    const inner = protocol.snapshot()
    return { message, connected: inner.sessionOpen, peers: connected.slice(), serverReady: ready && !nativeFailed,
      nativeStage, advertising, error: lastError, paused: inner.paused, peer: inner.peer, state: inner.state,
      failureStage: inner.stage, busy: inner.busy, pending: inner.requestPending, replies: inner.replies,
      reads: inner.reads, writes: inner.writes, saved: inner.savedDays }
  }
  function publish(next) {
    if (typeof next === 'string') message = next
    if (hooks.state) { try { hooks.state(snapshot()) } catch (_) {} }
  }

  /** Re-request advertising. Safe to call repeatedly; the watchdog relies on that. */
  function advertise() {
    if (stopped || !server || connected.length) return
    try { server.startAdvertising(ADVERTISE_SETTING, ADVERTISE_DATA) } catch (_) {}
    advertising = true; lastAdvertiseAt = clock()
    publish()
  }
  function queueAdvertise() {
    if (stopped || advertiseTimer) return
    advertiseTimer = delay(() => { advertiseTimer = null; advertise() }, ADVERTISE_DELAY)
  }

  function releaseServer() {
    const current = server
    server = null; ready = false; advertising = false
    if (!current) return
    try { current.stopAdvertising() } catch (_) {}
    ;['unsubscribeCharacteristicRead', 'unsubscribeCharacteristicWrite', 'unsubscribeConnectStateChange']
      .forEach(name => { try { current[name]() } catch (_) {} })
    // The documentation never says close() removes services, so remove them first and close last.
    try { current.removeService(SERVICE) } catch (_) {}
    try { current.close() } catch (_) {}
  }

  function nativeFailure(failureStage, code) {
    nativeFailed = true; ready = false; advertising = false
    lastError = (STAGE_LABEL[failureStage] || failureStage) + (typeof code === 'number' ? ' · ' + code : '')
    nativeStage = failureStage
    publish('直连服务启动失败 · ' + lastError)
  }

  function onRead(request) {
    let value = new Uint8Array(0), status = 0
    try {
      if (String(request.characteristicUuid || '').toLowerCase() === CHARACTERISTIC) value = protocol.read(request)
      else status = 7
    } catch (_) { value = new Uint8Array(0); status = 7 }
    try {
      server.sendResponse({ deviceId: request.deviceId, transId: request.transId, status,
        offset: request.offset || 0, value: wire(value) })
    } catch (_) {}
    publish()
  }

  function onWrite(request) {
    let accepted = false
    try {
      accepted = String(request.characteristicUuid || '').toLowerCase() === CHARACTERISTIC && protocol.write(request)
    } catch (_) { accepted = false }
    // needRsp is documented on the write request; only answer when the client asked for a response.
    if (request.needRsp !== false) {
      try {
        server.sendResponse({ deviceId: request.deviceId, transId: request.transId, status: accepted ? 0 : 6,
          offset: request.offset || 0, value: new ArrayBuffer(0) })
      } catch (_) {}
    }
    publish()
  }

  function onConnection(info) {
    if (stopped) return
    const id = peerId(info && info.deviceId)
    if (!id) return
    // The parameter table names this field `transId` while the official example reads `state`.
    const state = typeof info.state === 'number' ? info.state : typeof info.transId === 'number' ? info.transId : -1
    if (state === 2) {
      if (connected.indexOf(id) < 0) connected = connected.concat(id)
      advertising = false
      publish('手机已连接 · 正在建立会话')
      return
    }
    if (state !== 0 && state !== 3) return
    connected = connected.filter(peer => peer !== id)
    advertising = false
    const inner = protocol.snapshot()
    // Drop the session for this peer so a reconnect always starts with a fresh handshake.
    if (!inner.peer || inner.peer === id) protocol.disconnect('手机已断开 · 等待重连，记录可离线查看')
    if (!connected.length) queueAdvertise()
  }

  /** Register the service and all three subscriptions. Returns true when the server is usable. */
  function open() {
    lastOpenAt = clock()
    nativeFailed = false; lastError = ''
    releaseServer()
    try {
      nativeStage = 'create'
      server = ble.BLE.createGattServer()
      if (!server) throw new Error('createGattServer')
      nativeStage = 'add-service'
      const added = server.addService({ serviceUuid: SERVICE, isPrimary: true,
        characteristics: [{ serviceUuid: SERVICE, characteristicUuid: CHARACTERISTIC,
          characteristicValue: wire(protocol.initialValue()), descriptors: [],
          properties: CHARACTERISTIC_PROPERTIES.slice() }], includeServices: [] })
      if (added === false) throw new Error('addService')
      nativeStage = 'subscribe-read'
      server.subscribeCharacteristicRead({ callback: onRead, fail: (_, code) => nativeFailure('subscribe-read', code) })
      // Check after every subscription: a `fail` callback can fire synchronously, and the stage it
      // reports is what tells the user which native interface refused to start.
      if (nativeFailed) throw new Error('subscribe-read')
      nativeStage = 'subscribe-write'
      server.subscribeCharacteristicWrite({ callback: onWrite, fail: (_, code) => nativeFailure('subscribe-write', code) })
      if (nativeFailed) throw new Error('subscribe-write')
      nativeStage = 'subscribe-connection'
      server.subscribeConnectStateChange({ callback: onConnection, fail: (_, code) => nativeFailure('subscribe-connection', code) })
      if (nativeFailed) throw new Error('subscribe-connection')
      nativeStage = 'advertise'
      advertising = false
      advertise()
      ready = true; nativeStage = 'running'
      publish(identity.secret ? '直连服务已开启 · 手机可直接重连' : '直连服务已开启，在手机搜索并连接')
      return true
    } catch (error) {
      if (!nativeFailed) {
        nativeFailed = true
        lastError = (STAGE_LABEL[nativeStage] || nativeStage) +
          (error && typeof error.code === 'number' ? ' · ' + error.code : '')
        publish('直连服务启动失败 · ' + lastError)
      }
      releaseServer()
      return false
    }
  }

  /** The single place that decides whether the link is still reachable. */
  function tick() {
    if (stopped) return
    if (!server || !ready) {
      if (clock() - lastOpenAt >= REOPEN_AFTER) open()
      return
    }
    if (connected.length) return
    if (clock() - lastAdvertiseAt >= READVERTISE_AFTER) advertise()
  }

  return {
    start() {
      if (!stopped) return
      stopped = false
      open()
      if (!health) health = repeat(tick, HEALTH_INTERVAL)
    },
    close() {
      stopped = true
      if (health) cancelRepeat(health); health = null
      if (advertiseTimer) cancelDelay(advertiseTimer); advertiseTimer = null
      connected = []
      protocol.close()
      releaseServer()
      publish('直连服务已关闭')
    },
    /** Called when the dashboard becomes visible again; never rebuilds a healthy server. */
    ensureServer() {
      if (stopped) return false
      if (!server || !ready) return open()
      if (!connected.length && !advertising) advertise()
      return true
    },
    pauseEnrolment() {
      const paused = protocol.pauseEnrolment()
      if (paused) publish('正在清除旧手机连接')
      return paused
    },
    resumeEnrolment() { protocol.resumeEnrolment() },
    /** A user-initiated update request only flips a flag; the receipt carries it to the phone. */
    request() { publish(protocol.snapshot().sessionOpen ? '更新请求已保留，等待手机传输' : '更新请求已保留，等待手机连接') },
    snapshot, publish
  }
}
