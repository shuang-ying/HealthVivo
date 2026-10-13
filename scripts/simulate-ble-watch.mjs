// Runs production BlueOS GATT and Home handlers against Node's independent native crypto.
// Synthetic records and ephemeral identities only. No Android device or model API is used.
import fs from 'node:fs'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import dashboard from '../watch/src/lib/dashboard.mjs'
import { createBleServer, SERVICE, RX, STATUS } from '../watch/src/lib/ble-transport.mjs'
import { sha256, hmac, utf8, b64, unb64 } from '../watch/src/lib/ble-wire.mjs'
import sample from '../watch/simulator/sample.mjs'
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function eventually(condition) {
  const deadline = Date.now() + 3000
  while(!condition()) { assert.ok(Date.now() < deadline, 'Simulation callback timed out'); await pause(5) }
}
for (const text of ['', 'abc', '身体记录🥗', 'x'.repeat(2048)]) {
  assert.equal(Buffer.from(sha256(utf8(text))).toString('hex'), crypto.createHash('sha256').update(text).digest('hex'))
  assert.equal(Buffer.from(hmac(utf8('fixture-key'), utf8(text))).toString('hex'), crypto.createHmac('sha256', 'fixture-key').update(text).digest('hex'))
  assert.equal(Buffer.from(unb64(b64(utf8(text)))).toString(), text)
}
const keys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
const privateKey = keys.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64')
const script = fs.readFileSync(new URL('../watch/src/Home/index.ux', import.meta.url), 'utf8')
  .split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm, '').replace('export default', 'return')
let cache = '', storeCallback = null, failStore = false, holdStore = false, page = null, nativeAesCalls = 0
let events = {}, replies = new Map(), transaction = 0, server = null
const storage = {
  get(options) { setTimeout(() => options.success(cache || options.default), 1) },
  set(options) {
    storeCallback = () => { if(failStore) options.fail(); else { cache = options.value; options.success() } }
    if(!holdStore) setTimeout(storeCallback, 1)
  }
}
const cipher = {
  rsa(options) { setTimeout(() => {
    try { assert.equal(options.transformation, 'RSA/None/OAEPwithSHA-256andMGF1Padding')
      options.success({ text: crypto.privateDecrypt({ key: keys.privateKey, oaepHash: 'sha256', padding: crypto.constants.RSA_PKCS1_OAEP_PADDING }, Buffer.from(options.text, 'base64')).toString('utf8') })
    } catch (error) { options.fail('', 200) }
  }, 1) },
  aes(options) { nativeAesCalls++; setTimeout(() => {
    try { assert.equal(options.transformation, 'AES/CBC/PKCS5Padding')
      const aes = crypto.createDecipheriv('aes-256-cbc', Buffer.from(options.key, 'base64'), Buffer.from(options.iv, 'base64'))
      options.success({ text: Buffer.concat([aes.update(Buffer.from(options.text, 'base64')), aes.final()]).toString('utf8') })
    } catch (error) { options.fail('', 200) }
  }, 1) }
}
const bluetooth = { BLE: { createGattServer() {
  events = {}; replies = new Map()
  server = {
    addService(value) { assert.equal(value.serviceUuid, SERVICE); assert.deepEqual(value.characteristics.map(c => c.properties), [[5], [4]]); return true },
    startAdvertising(setting, value) { assert.ok(setting.connectable); assert.deepEqual(value.serviceUuids, [SERVICE]); return true },
    stopAdvertising() {}, close() {},
    subscribeCharacteristicRead(options) { events.read = options.callback },
    subscribeCharacteristicWrite(options) { events.write = options.callback },
    subscribeConnectStateChange(options) { events.connect = options.callback },
    unsubscribeCharacteristicRead() {}, unsubscribeCharacteristicWrite() {}, unsubscribeConnectStateChange() {},
    sendResponse(value) { replies.set(value.transId, value); return true }
  }
  return server
} } }
const ble = { open(hooks) { return createBleServer(bluetooth, cipher, { privateKey }, hooks) } }
function instance() {
  const options = new Function('storage', 'ble', 'dashboard', 'brightness', 'createOrbitVClient', script)(storage, ble, dashboard, { setKeepScreenOn() {} }, () => null)
  const data = { ...options.data }, methods = {}, staticData = { runtime: {} }
  const context = new Proxy({}, {
    get(_, key) { return key === '$element' ? () => ({ scrollTo() {} }) : Object.hasOwn(data, key) ? data[key] : methods[key] || staticData[key] },
    set(_, key, value) { data[key] = value; return true }
  })
  for (const [key, value] of Object.entries(options)) if(typeof value === 'function' && key !== 'render') methods[key] = value.bind(context)
  context.onInit(); context.runtime.mode = 'ble'; context.onReady(); context.onShow()
  return context
}
function write(value, accepted = true, peer = 'fixture-phone') {
  const id = ++transaction
  events.write({ serviceUuid: SERVICE, characteristicUuid: RX, deviceId: peer, transId: id, offset: 0, isPrep: false, needRsp: true, value: Uint8Array.from(value).buffer })
  assert.equal(replies.get(id)?.status, accepted ? 0 : 1)
}
function read(peer = 'fixture-phone') {
  const id = ++transaction
  events.read({ serviceUuid: SERVICE, characteristicUuid: STATUS, deviceId: peer, transId: id, offset: 0 })
  const reply = replies.get(id); assert.equal(reply.status, 0)
  return Buffer.from(reply.value)
}
function number(value) { const out = Buffer.alloc(4); out.writeUInt32BE(value >>> 0); return out }
function transmit(value, id, kind = 2, limit = 20) {
  write(Buffer.concat([Buffer.from([1]), number(id), Buffer.from([kind]), number(value.length)]))
  for(let offset = 0; offset < value.length; offset += limit - 9) write(Buffer.concat([Buffer.from([2]), number(id), number(offset), value.subarray(offset, offset + limit - 9)]))
  write(Buffer.concat([Buffer.from([3]), number(id)]))
}
let sessionKey = crypto.randomBytes(32), nonce = crypto.randomBytes(16)
const macKey = crypto.createHash('sha256').update(sessionKey.toString('base64') + ':vitality-mac:' + nonce.toString('base64')).digest()
function receipt() {
  const value = read(); assert.equal(value.length, 20)
  assert.deepEqual(value.subarray(7), crypto.createHmac('sha256', macKey).update(value.subarray(0, 7)).digest().subarray(0, 13))
  return { state: value[0], id: value.readUInt32BE(1), progress: value.readUInt16BE(5) }
}
function encrypted(snapshot, id) {
  const iv = crypto.randomBytes(16), aes = crypto.createCipheriv('aes-256-cbc', sessionKey, iv)
  const body = Buffer.concat([iv, aes.update(JSON.stringify(snapshot)), aes.final()])
  return Buffer.concat([body, crypto.createHmac('sha256', macKey).update(Buffer.concat([number(id), body])).digest()])
}
page = instance(); await eventually(() => page.runtime.connection)
const hello = crypto.publicEncrypt({ key: keys.publicKey, oaepHash: 'sha256', padding: crypto.constants.RSA_PKCS1_OAEP_PADDING },
  Buffer.from(JSON.stringify({ key: sessionKey.toString('base64'), nonce: nonce.toString('base64') })))
transmit(hello, 11, 1); await eventually(() => read().length === 20)
assert.equal(receipt().state, 1)
const confirmation = crypto.createHash('sha256').update(sessionKey.toString('base64') + ':' + nonce.toString('base64')).digest('hex').slice(0, 6).toUpperCase()
assert.equal(page.pairingCode, confirmation)
write(Buffer.concat([Buffer.from([1]), number(12), Buffer.from([2]), number(100)]), false)
page.approve(); assert.equal(receipt().state, 2); assert.equal(page.pairingCode, '')
page.refresh(); assert.equal(receipt().state, 2); assert.equal(receipt().id, 11); assert.ok(receipt().progress & 0x8000)
const first = sample(); first.meals[0].foods[0].name += '🥗'
holdStore = true
transmit(encrypted(first, 21), 21)
await eventually(() => storeCallback !== null); assert.equal(receipt().state, 3); assert.equal(page.runtime.snapshot, null)
assert.ok(storeCallback); storeCallback()
assert.equal(receipt().state, 4); assert.equal(receipt().id, 21)
assert.equal(page.runtime.snapshot.steps, 6800)
const pages = {}; for(const key of ['home', 'energy', 'exercise', 'meals', 'advice']) { page.select(key); assert.ok(page.items.length); pages[key] = page.items.length }
holdStore = false
page.refresh(); assert.equal(receipt().state, 4); assert.equal(receipt().id, 21); assert.ok(receipt().progress & 0x8000)
const next = { ...first, revision: first.revision + 100, steps: 0 }
const value = encrypted(next, 22)
write(Buffer.concat([Buffer.from([1]), number(22), Buffer.from([2]), number(value.length)]))
write(Buffer.concat([Buffer.from([2]), number(22), number(1), value.subarray(0, 11)]), false)
assert.equal(page.runtime.snapshot.revision, first.revision)
transmit(value, 22, 2, 180); await eventually(() => receipt().state !== 3)
assert.equal(receipt().state, 4); assert.equal(page.runtime.snapshot.steps, 0)
const committed = page.runtime.snapshot.revision
failStore = true
transmit(encrypted({ ...next, revision: committed + 100 }, 23), 23); await eventually(() => receipt().state !== 3)
assert.equal(receipt().state, 5); assert.equal(page.runtime.snapshot.revision, committed)
failStore = false
const corrupt = encrypted({ ...next, revision: committed + 200 }, 24); corrupt[20] ^= 1
const callsBefore = nativeAesCalls
transmit(corrupt, 24); await eventually(() => receipt().state !== 3)
assert.equal(receipt().state, 5); assert.equal(nativeAesCalls, callsBefore, 'MAC verified before decryption')
assert.equal(page.runtime.snapshot.revision, committed)
assert.equal(read('other-phone').length, 1)
write(Buffer.concat([Buffer.from([1]), number(99), Buffer.from([1]), number(256)]), false, 'other-phone')
transmit(encrypted(first, 25), 25); await eventually(() => receipt().state !== 3)
assert.equal(receipt().state, 5); assert.equal(page.runtime.snapshot.revision, committed)
page.onHide(); page = instance(); await eventually(() => page.runtime.loaded)
assert.equal(page.runtime.snapshot.revision, committed)
page.onDestroy()
const report = { syntheticOnly: true, nativeBluetoothVerified: false, nativeBlueOsCipherVerified: false, pages,
  verified: ['standard SHA-256/HMAC vectors', 'RSA/AES/HMAC exchange', '20-byte GATT writes and status reads', '180-byte writes', 'pairing approval gate', 'storage-before-ACK', 'refresh preserves handshake and save receipts', 'watch pull', 'unicode food names', 'zero steps', 'invalid fragment offsets', 'partial-transfer cache retention', 'failed-write retention', 'MAC rejection before decryption', 'peer isolation', 'stale snapshot rejection', 'offline reopening'],
  timestamp: new Date().toISOString() }
fs.mkdirSync(new URL('../artifacts/', import.meta.url), { recursive: true })
fs.writeFileSync(new URL('../artifacts/watch-ble-simulation-0.4.1.json', import.meta.url), JSON.stringify(report, null, 2))
console.log(JSON.stringify(report, null, 2))
