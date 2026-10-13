import { bytes, utf8, join, u32, read32, b64, unb64, sha256, hmac, equal, hex } from './ble-wire.mjs'

export const SERVICE = '9f7c7200-1771-42b9-bc36-d88bfb0952a0'
export const RX = '9f7c7201-1771-42b9-bc36-d88bfb0952a0'
export const STATUS = '9f7c7202-1771-42b9-bc36-d88bfb0952a0'
const MAX = 200 * 1024

/** No relay, no notifications: writable data + a 20-byte authenticated read receipt. */
export function createBleServer(bluetooth, cipher, identity, hooks) {
  let server = null, closed = false, peer = '', session = null, assembly = null, busy = false
  let state = 0, transfer = 0, progress = 0, timer = null, generation = 0, attempts = []
  let writes = 0, reads = 0, diagnosticPhase = '等待手机'
  const diagnostic = text => {
    if (text) diagnosticPhase = text
    if (!closed && hooks.diagnostics) hooks.diagnostics('读 ' + reads + ' · 写 ' + writes + ' · ' + diagnosticPhase)
  }
  const message = text => { if (!closed) hooks.message(text) }
  const setState = (value, id, amount) => { state = value; transfer = id >>> 0; progress = amount || 0 }
  const cancelTimer = () => { if (timer) clearTimeout(timer); timer = null }
  const response = request => {
    if (!session || request.deviceId !== peer) return new Uint8Array([state === 5 ? 5 : 0])
    const head = join(new Uint8Array([state]), u32(transfer), new Uint8Array([progress >>> 8, progress & 255]))
    return join(head, hmac(session.mac, head).slice(0, 13))
  }
  const fail = (code, id) => {
    assembly = null; busy = false; cancelTimer(); setState(5, id || transfer, code)
    message('接收 / 校验 / 保存失败（' + code + '），保留上次缓存；请重连或刷新。')
  }
  const native = (method, options) => new Promise((resolve, reject) => {
    let done = false
    const finish = (error, text) => { if (done) return; done = true; clearTimeout(deadline); if (error) reject(error); else resolve(text) }
    const deadline = setTimeout(() => finish(new Error(method + ' 超时')), 15000)
    try { cipher[method](Object.assign({}, options, { success: result => finish(null, result.text),
      fail: (_, code) => finish(new Error(method + ' 错误 ' + (typeof code === 'number' ? code : '未知'))) })) }
    catch (error) { finish(new Error(method + ' 接口不可用')) }
  })
  const receive = async packet => {
    const active = generation
    busy = true
    try {
      if (packet.kind === 1) {
        diagnostic('连接包已接收，RSA 解密中')
        const plain = await native('rsa', { action: 'decrypt', text: b64(packet.data), key: identity.privateKey, transformation: 'RSA/None/OAEPwithSHA-256andMGF1Padding' })
        if (closed || active !== generation) return
        const hello = JSON.parse(plain)
        if (unb64(hello.key).length !== 32 || unb64(hello.nonce).length !== 16) throw new Error('Session')
        session = { key: hello.key, nonce: hello.nonce, approved: false, mac: sha256(utf8(hello.key + ':vitality-mac:' + hello.nonce)) }
        const code = hex(sha256(utf8(hello.key + ':' + hello.nonce)).slice(0, 3)).toUpperCase()
        setState(1, packet.id, 0); busy = false
        diagnostic('已解密，等待允许')
        hooks.pairing(code); message('确认码 ' + code + '，与手机一致时允许连接。')
        return
      }
      if (!session || !session.approved || packet.data.length < 64) throw new Error('Unapproved')
      const data = packet.data, body = data.slice(0, data.length - 32), tag = data.slice(data.length - 32)
      if (!equal(tag, hmac(session.mac, join(u32(packet.id), body)))) throw new Error('Authentication')
      const iv = body.slice(0, 16), encrypted = body.slice(16)
      if (!encrypted.length || encrypted.length % 16) throw new Error('Ciphertext')
      const plain = await native('aes', { action: 'decrypt', text: b64(encrypted), key: session.key, transformation: 'AES/CBC/PKCS5Padding', iv: b64(iv), ivOffset: 0, ivLen: 16 })
      if (closed || active !== generation) return
      message('数据已接收，正在保存…')
      const snapshot = JSON.parse(plain)
      hooks.snapshot(snapshot, saved => {
        if (closed || active !== generation) return
        busy = false
        if (saved) { setState(4, packet.id, 100); diagnostic('保存完成'); message('手机数据已保存，可离线查看') }
        else fail(4, packet.id)
      })
    } catch (error) { if (!closed && active === generation) {
      fail(3, packet.id)
      // Only controlled error labels/codes, never plaintext, keys or exception payloads.
      const detail = /^((rsa|aes) (超时|接口不可用|错误 (未知|\d+)))$/.test(error.message) ? error.message : '格式或校验失败'
      diagnostic(detail); message('连接 / 数据处理失败：' + detail + '，请重连。')
    } }
  }
  const accept = request => {
    const value = bytes(request.value)
    if (request.offset || request.isPrep || value.length < 5 || value.length > 512 || busy) return false
    const op = value[0], id = read32(value, 1)
    if (!id || (peer && request.deviceId !== peer)) return false
    if (op === 1) {
      if (value.length !== 10) return false
      const kind = value[5], length = read32(value, 6)
      if (length < 1 || length > MAX || (kind !== 1 && kind !== 2)) return false
      if (kind === 1) {
        if (session && session.approved) return false
        attempts = attempts.filter(time => Date.now() - time < 30000)
        if (attempts.length >= 3 || length !== 256) return false
        attempts.push(Date.now()); generation++; session = null; hooks.pairing('')
      } else if (!session || !session.approved) return false
      peer = request.deviceId
      diagnostic(kind === 1 ? '接收连接包' : '接收今日摘要')
      assembly = { id, kind, length, offset: 0, data: new Uint8Array(length) }
      setState(3, id, 0)
    } else {
      if (!assembly || assembly.id !== id) return false
      if (op === 2) {
        if (value.length <= 9 || read32(value, 5) !== assembly.offset || assembly.offset + value.length - 9 > assembly.length) return false
        assembly.data.set(value.slice(9), assembly.offset); assembly.offset += value.length - 9
        progress = Math.floor(assembly.offset * 100 / assembly.length)
        if (assembly.kind === 2) message('蓝牙接收 ' + progress + '%')
      } else if (op === 3) {
        if (value.length !== 5 || assembly.offset !== assembly.length) return false
        const packet = assembly; assembly = null; cancelTimer(); receive(packet)
        return true
      } else return false
    }
    cancelTimer(); timer = setTimeout(() => fail(2, id), 90000)
    return true
  }
  const api = {
    approve() {
      if (closed || !session || state !== 1) return
      session.approved = true; setState(2, transfer, 0); hooks.pairing(''); message('手机已获准直连，等待今日数据…')
    },
    reject() {
      if (!session || state !== 1) return
      setState(7, transfer, 0); hooks.pairing(''); message('已拒绝这台手机。')
    },
    request() {
      if (session && session.approved && !busy && !assembly) {
        // Keep handshake/save/failure receipts readable until the next BEGIN.
        // Refresh must not erase a receipt before the phone's next poll.
        if (state === 2 || state === 4 || state === 5) progress |= 0x8000
        else setState(6, 0, 0)
        message('已请求手机数据，等待直连传送…')
      }
      else if (!session) message('直连服务已开启，请在手机搜索附近手表。')
    },
    close() {
      closed = true; generation++; cancelTimer(); assembly = null; session = null
      if (server) {
        try { server.stopAdvertising() } catch (error) {}
        ;['unsubscribeCharacteristicRead','unsubscribeCharacteristicWrite','unsubscribeConnectStateChange'].forEach(name => { try { server[name]() } catch (error) {} })
        try { server.close() } catch (error) {}
      }
    }
  }
  let stage = '创建服务'
  try {
    if (!identity.privateKey || !bluetooth.BLE || !bluetooth.BLE.createGattServer) throw new Error('BLE server unavailable')
    server = bluetooth.BLE.createGattServer()
    const characteristic = (uuid, properties) => ({ serviceUuid: SERVICE, characteristicUuid: uuid, characteristicValue: new ArrayBuffer(0), descriptors: [], properties })
    stage = '添加服务'
    if (server.addService({ serviceUuid: SERVICE, isPrimary: true, characteristics: [characteristic(RX, [5]), characteristic(STATUS, [4])], includeServices: [] }) === false) throw new Error('addService')
    stage = '订阅读取'
    server.subscribeCharacteristicRead({ callback: request => {
      reads++; diagnostic()
      const valid = request.serviceUuid.toLowerCase() === SERVICE && request.characteristicUuid.toLowerCase() === STATUS
      const value = valid && !request.offset ? response(request) : new Uint8Array(0)
      server.sendResponse({ deviceId: request.deviceId, transId: request.transId, status: valid ? 0 : 1, offset: request.offset || 0, value: value.buffer })
    }, fail: () => message('蓝牙读取监听不可用，请重新打开应用。') })
    stage = '订阅写入'
    server.subscribeCharacteristicWrite({ callback: request => {
      writes++; diagnostic()
      let accepted = false
      try { accepted = request.serviceUuid.toLowerCase() === SERVICE && request.characteristicUuid.toLowerCase() === RX && accept(request) } catch (error) {}
      if (request.needRsp) server.sendResponse({ deviceId: request.deviceId, transId: request.transId, status: accepted ? 0 : 1, offset: request.offset || 0, value: new ArrayBuffer(0) })
    }, fail: () => message('蓝牙写入监听不可用，请重新打开应用。') })
    stage = '订阅连接'
    server.subscribeConnectStateChange({ callback: info => {
      if (closed) return
      const value = typeof info.state === 'number' ? info.state : info.transId
      if (value === 0 && info.deviceId === peer) {
        generation++; session = null; assembly = null; peer = ''; busy = false; cancelTimer(); hooks.pairing('')
        message('手机已断开，显示离线缓存；可重新搜索连接。')
        try { server.startAdvertising({ interval: 1600, txPower: 0, connectable: true }, { serviceUuids: [SERVICE] }) } catch (error) {}
      }
    }, fail: () => message('蓝牙连接监听不可用，请重新打开应用。') })
    stage = '开启广播'
    if (server.startAdvertising({ interval: 1600, txPower: 0, connectable: true }, { serviceUuids: [SERVICE] }) === false) throw new Error('Advertising')
    diagnostic('服务已开启')
    message('蓝牙直连服务已开启，请在手机搜索附近手表。')
    return api
  } catch (error) { api.close(); hooks.message('BLE 直连失败：' + stage + (typeof error.code === 'number' ? '（' + error.code + '）' : '') + '；请确认蓝牙权限与系统支持。'); return null }
}
