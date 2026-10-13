// Real Android phone -> Windows BLE radio -> production watch GATT + Home handlers.
// Native BlueOS BLE/cipher are represented by Windows/Node. No model APIs are called.
import fs from 'node:fs'
import path from 'node:path'
import net from 'node:net'
import http from 'node:http'
import crypto from 'node:crypto'
import dashboard from '../watch/src/lib/dashboard.mjs'
import { createBleServer, SERVICE, RX, STATUS } from '../watch/src/lib/ble-transport.mjs'

const { fileURLToPath } = await import('node:url')
const local = fileURLToPath(new URL('../.local/watch-ble-live/', import.meta.url))
fs.mkdirSync(local, { recursive: true })
const identity = JSON.parse(fs.readFileSync(new URL('../.local/watch-ble-identity.json', import.meta.url), 'utf8'))
const privateKey = crypto.createPrivateKey({ key: Buffer.from(identity.privateKey, 'base64'), type: 'pkcs8', format: 'der' })
const cacheKey = crypto.createHash('sha256').update(identity.privateKey + ':desktop-simulator-cache').digest()
const token = crypto.randomBytes(24).toString('hex')
fs.writeFileSync(path.join(local, 'control.json'), JSON.stringify({ port: 18765, token }))
const metrics = { startedAt: new Date().toISOString(), realBluetooth: true, androidSynthetic: false,
  nativeBlueOsBluetoothVerified: false, nativeBlueOsCipherVerified: false,
  writes: 0, reads: 0, rejectedWrites: 0, maxWriteBytes: 0, saved: 0, savedBeforeReceipt: false,
  receiptState: 0, snapshotBytes: 0, viewSections: [], errors: [] }
let cache = '', failNextStore = false, page, events = {}, replies = new Map(), transaction = 0, peer = ''
const report = () => {
  const result = { ...metrics, phase: page?.connectionText || '', pairingCode: page?.pairingCode || '',
    cacheExists: fs.existsSync(path.join(local, 'cache.enc')), acknowledged: metrics.saved > 0 && metrics.receiptState === 4 }
  // Report contains transport diagnostics only, without meals, health values, advice or keys.
  fs.writeFileSync(new URL('../artifacts/phone-live-watch-connection.json', import.meta.url), JSON.stringify(result, null, 2))
  return result
}
const storage = {
  get(options) { setImmediate(() => options.success(cache || options.default)) },
  set(options) {
    const plain = options.value
    if (failNextStore) { failNextStore = false; setImmediate(() => options.fail()); return }
    // Atomic encrypted local cache; callback only after the file has been committed.
    const iv = crypto.randomBytes(12), aes = crypto.createCipheriv('aes-256-gcm', cacheKey, iv)
    const encrypted = Buffer.concat([aes.update(plain, 'utf8'), aes.final()])
    const temporary = path.join(local, 'cache.tmp'), destination = path.join(local, 'cache.enc')
    fs.writeFile(temporary, Buffer.concat([iv, aes.getAuthTag(), encrypted]), error => {
      if (error) { options.fail(); return }
      try { fs.renameSync(temporary, destination); cache = plain; metrics.saved++; options.success(); report() }
      catch { options.fail() }
    })
  }
}
const cipher = {
  rsa(options) { setImmediate(() => {
    try {
      const text = crypto.privateDecrypt({ key: privateKey, oaepHash: 'sha256', padding: crypto.constants.RSA_PKCS1_OAEP_PADDING }, Buffer.from(options.text, 'base64')).toString('utf8')
      options.success({ text })
    } catch { metrics.errors.push('rsa-decrypt'); options.fail('', 200); report() }
  }) },
  aes(options) { setImmediate(() => {
    try {
      const aes = crypto.createDecipheriv('aes-256-cbc', Buffer.from(options.key, 'base64'), Buffer.from(options.iv, 'base64'))
      const text = Buffer.concat([aes.update(Buffer.from(options.text, 'base64')), aes.final()]).toString('utf8')
      metrics.snapshotBytes = Buffer.byteLength(text); options.success({ text })
    } catch { metrics.errors.push('aes-decrypt'); options.fail('', 200); report() }
  }) }
}
const bluetooth = { BLE: { createGattServer() {
  events = {}; replies = new Map()
  return {
    addService(value) { if (value.serviceUuid !== SERVICE) throw new Error('Wrong simulator service'); return true },
    startAdvertising() { return true }, stopAdvertising() {}, close() {},
    subscribeCharacteristicRead(options) { events.read = options.callback },
    subscribeCharacteristicWrite(options) { events.write = options.callback },
    subscribeConnectStateChange(options) { events.connect = options.callback },
    unsubscribeCharacteristicRead() {}, unsubscribeCharacteristicWrite() {}, unsubscribeConnectStateChange() {},
    sendResponse(value) { replies.set(value.transId, value); return true }
  }
} } }
const ble = { open(hooks) { return createBleServer(bluetooth, cipher, identity, hooks) } }
const script = fs.readFileSync(new URL('../watch/src/Home/index.ux', import.meta.url), 'utf8')
  .split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm, '').replace('export default', 'return')
const options = new Function('storage', 'ble', 'dashboard', 'brightness', 'createOrbitVClient', script)(storage, ble, dashboard, { setKeepScreenOn() {} }, () => null)
const data = { ...options.data }, methods = {}, staticData = { runtime: {} }
page = new Proxy({}, {
  get(_, key) { return key === '$element' ? () => ({ scrollTo() {} }) : Object.hasOwn(data, key) ? data[key] : methods[key] || staticData[key] },
  set(_, key, value) { data[key] = value; return true }
})
for (const [key, value] of Object.entries(options)) if (typeof value === 'function') methods[key] = value.bind(page)
page.onInit(); page.runtime.mode = 'ble'; page.onReady(); page.onShow()

const radio = net.createServer(socket => {
  socket.setTimeout(5000, () => socket.destroy())
  let buffer = '', processed = false
  socket.on('data', value => {
    if (processed) return
    buffer += value.toString('utf8')
    if (buffer.length > 4096) { socket.destroy(); return }
    if (!buffer.includes('\n')) return
    processed = true
    try {
      const [operation, encodedPeer, offsetText, encodedValue] = buffer.trimEnd().split('\t')
      const deviceId = Buffer.from(encodedPeer, 'base64').toString('utf8'), offset = Number(offsetText)
      if (!deviceId || !Number.isInteger(offset) || offset < 0) throw new Error('Bad request')
      if (peer && peer !== deviceId) throw new Error('Unexpected peer')
      if (!peer) peer = deviceId
      const transId = ++transaction
      if (operation === 'W') {
        const payload = Buffer.from(encodedValue || '', 'base64')
        metrics.writes++; metrics.maxWriteBytes = Math.max(metrics.maxWriteBytes, payload.length)
        events.write({ serviceUuid: SERVICE, characteristicUuid: RX, deviceId, transId, offset, isPrep: false, needRsp: true, value: Uint8Array.from(payload).buffer })
      } else if (operation === 'R') {
        metrics.reads++; events.read({ serviceUuid: SERVICE, characteristicUuid: STATUS, deviceId, transId, offset })
      } else throw new Error('Unknown operation')
      const reply = replies.get(transId); replies.delete(transId)
      if (!reply || reply.status !== 0) { if (operation === 'W') metrics.rejectedWrites++; socket.end('ERR\n'); report(); return }
      const result = Buffer.from(reply.value)
      if (operation === 'R' && result.length === 20) {
        metrics.receiptState = result[0]
        if (result[0] === 4) metrics.savedBeforeReceipt = metrics.saved > 0 && fs.existsSync(path.join(local, 'cache.enc'))
      }
      socket.end(operation === 'R' ? 'OK\t' + result.toString('base64') + '\n' : 'OK\n')
      report()
    } catch { socket.end('ERR\n') }
  })
  socket.on('error', () => {})
})
const html = `<!doctype html><meta charset="utf-8"><title>活力记录 · 实时模拟手表</title>
<style>body{background:#18151f;color:#f3ecff;font:16px system-ui;margin:24px}main{width:380px;margin:auto}article{padding:20px;background:#292233;border-radius:22px;margin:12px 0;white-space:pre-wrap}button{border:0;border-radius:18px;background:#9774ed;color:white;padding:15px;margin:6px;cursor:pointer}small{color:#beb0cf}h1{font-size:28px}</style>
<main><small>Windows 真实蓝牙 · BlueOS 业务逻辑仿真</small><h1 id="title"></h1><p id="phase"></p><article id="pair"></article><button id="approve">核对后允许手机</button><button id="refresh">刷新手机数据</button><button id="home">总览</button><section id="rows"></section></main>
<script>const token=new URLSearchParams(location.search).get('token');let state;async function command(action,extra={}){await fetch('/'+action,{method:'POST',headers:{'X-Simulator-Token':token,'Content-Type':'application/json'},body:JSON.stringify(extra)})}document.querySelector('#approve').onclick=()=>command('approve',{code:state.pairingCode});document.querySelector('#refresh').onclick=()=>command('refresh');document.querySelector('#home').onclick=()=>command('page',{page:'home'});async function update(){const r=await fetch('/view',{headers:{'X-Simulator-Token':token}});if(!r.ok)return;state=await r.json();document.querySelector('#title').textContent=state.pageTitle;document.querySelector('#phase').textContent=state.connectionText;document.querySelector('#pair').textContent=state.pairingCode?'确认码 '+state.pairingCode:state.savedText;document.querySelector('#approve').hidden=!state.pairingCode;document.querySelector('#rows').replaceChildren(...state.items.map(row=>{const el=document.createElement('article');el.textContent=row.title+'\\n'+row.body;if(row.action)el.onclick=()=>command('page',{page:row.action});return el}))}setInterval(update,1000);update()</script>`
const web = http.createServer(async (request, response) => {
  response.setHeader('Cache-Control', 'no-store'); response.setHeader('X-Content-Type-Options', 'nosniff')
  if (request.method === 'GET' && request.url.startsWith('/?')) { response.setHeader('Content-Type', 'text/html;charset=utf-8'); response.end(html); return }
  if (request.headers['x-simulator-token'] !== token) { response.writeHead(403); response.end(); return }
  if (request.method === 'GET' && request.url === '/status') { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(report())); return }
  if (request.method === 'GET' && request.url === '/view') { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(data)); return }
  if (request.method !== 'POST') { response.writeHead(405); response.end(); return }
  let text = ''
  for await (const chunk of request) { text += chunk; if (text.length > 1024) { response.writeHead(413); response.end(); return } }
  try {
    const body = text ? JSON.parse(text) : {}
    if (request.url === '/approve') { if (!page.pairingCode || body.code !== page.pairingCode) throw new Error('Code mismatch'); page.approve() }
    else if (request.url === '/refresh') page.refresh()
    else if (request.url === '/page') { page.select(body.page); if (!metrics.viewSections.includes(body.page)) metrics.viewSections.push(body.page) }
    else if (request.url === '/fail-next-save') failNextStore = true
    else throw new Error('Unknown action')
    report(); response.end('OK')
  } catch { response.writeHead(400); response.end('Invalid simulator control') }
})
radio.listen(18764, '127.0.0.1', () => console.log('Watch engine listening on loopback:18764'))
web.listen(18765, '127.0.0.1', () => console.log('Simulator UI listening on loopback:18765; local control token saved privately.'))
function close() { page.onDestroy(); report(); radio.close(); web.close(); setTimeout(() => process.exit(0), 300).unref() }
process.on('SIGINT', close); process.on('SIGTERM', close)
setTimeout(close, 20 * 60 * 1000).unref()
