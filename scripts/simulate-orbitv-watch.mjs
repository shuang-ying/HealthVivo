// Production extension, SDK, archive and page with synthetic data; no Bluetooth or real health records.
import fs from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { syncOrbitV } from '../watch/src/lib/orbitv-sync.mjs'
import { createArchive } from '../watch/src/lib/archive.mjs'
import dashboard from '../watch/src/lib/dashboard.mjs'
import sample from '../watch/simulator/sample.mjs'
import { validateLink } from '../orbitv/common/native-link.js'

const link = { schema: 1, appId: 'com.lrh.healthvivo.watch', baseUrl: 'http://127.0.0.1:28341', token: 'a'.repeat(64) }
validateLink(link)
assert.throws(() => validateLink({ ...link, baseUrl: 'http://192.168.1.1:28341' }))
const first = sample(); first.energy.referenceKcal = 2000; first.energy.intakeKcal = 1500
const previous = new Date(first.day + 'T12:00:00'); previous.setDate(previous.getDate() - 1)
const yesterday = previous.toISOString().slice(0, 10)
const legacy = { ...first, day: yesterday, revision: first.revision - 100 }
const nativeDays = new Map([[yesterday, { ...legacy, revision: first.revision - 50 }], [first.day, first]])
const nativeSessions = new Map(), nativeReceipts = new Map(), routes = new Map()
const records = new Map([['fitness.dashboard.v2', JSON.stringify({ snapshot: legacy, savedAt: Date.now() })]])
let ready = false, nativeClosed = false, gatewayClosed = false, transportReady = true, watchAppReady = false, watchVersion = '', command = 0, sequence = 0
let healthRequests = 0, confirmations = 0, failIndex = false, holdIndex = false, pendingIndex = null
const writes = []
const storage = {
  get(o) { queueMicrotask(() => o.success(records.get(o.key) || o.default)) },
  set(o) {
    const commit = () => { if (failIndex && o.key === 'fitness.index.v3') o.fail(); else { records.set(o.key, o.value); writes.push(o.key); o.success() } }
    if (holdIndex && o.key === 'fitness.index.v3') { holdIndex = false; pendingIndex = commit } else queueMicrotask(commit)
  },
  delete(o) { records.delete(o.key); o.success() }
}
const ov = {
  runtime: { async getInfo() { return { appId: link.appId } } },
  watch: { async getInfo() { return { connected: transportReady, transportReady } } },
  storage: { async get() { return link } },
  server: { route(method, path, handler) { routes.set(method + ' ' + path, handler) },
    json(body, options = {}) { return { code: options.status || 200, data: body } }, async ready() { ready = true } }
}
const browserFetch = async (url, options) => {
  assert.equal(options.headers.Authorization, 'Bearer ' + link.token)
  if (nativeClosed) throw new Error('synthetic network failure')
  const path = new URL(url).pathname; const request = options.body ? JSON.parse(options.body) : {}
  let result
  if (path === '/control' || path === '/heartbeat') {
    if (path === '/heartbeat') {
      if (!request.transportReady) watchAppReady = false
      else if (request.watchAppReady) watchAppReady = true
      if (request.watchVersion) watchVersion = request.watchVersion
    }
    result = { requestId: command, lastSavedAt: 0, message: '本机接口已开启', transportReady, watchAppConnected: watchAppReady, watchVersion }
  }
  else if (path === '/request') { command++; result = { requestId: command, message: '更新请求已发出' } }
  else if (path === '/sync') {
    healthRequests++
    const session = request.sessionId ? nativeSessions.get(request.sessionId) : (() => {
      const value = { id: 'session-' + ++sequence, command, days: [...nativeDays.values()].filter(day => request.known[day.day] !== day.revision).map(value => structuredClone(value)) }
      nativeSessions.set(value.id, value); return value
    })()
    assert.ok(session)
    const start = request.cursor || 0, days = session.days.slice(start, start + 1), cursor = start + days.length
    const receiptId = 'receipt-' + ++sequence
    nativeReceipts.set(receiptId, days)
    result = { schema: 3, sessionId: session.id, receiptId, command: session.command, days, cursor, more: cursor < session.days.length }
  } else if (path === '/saved') {
    const days = nativeReceipts.get(request.receiptId); assert.ok(days)
    assert.deepEqual(request.days, days.map(day => ({ day: day.day, revision: day.revision })))
    const index = records.has('fitness.index.v3') ? JSON.parse(records.get('fitness.index.v3')) : null
    for (const day of days) { assert.equal(index.days[day.day].revision, day.revision); assert.ok(records.has(index.days[day.day].key)) }
    confirmations++; result = { ok: true }
  } else throw new Error('unexpected native route')
  return { ok: true, async json() { return structuredClone(result) } }
}
vm.runInNewContext(fs.readFileSync(new URL('../orbitv/service.js', import.meta.url), 'utf8'), { ov, window: { ov }, fetch: browserFetch, AbortController, setTimeout, clearTimeout })
async function eventually(check) { const deadline = Date.now() + 2500; while (!check()) { assert.ok(Date.now() < deadline, 'async operation did not finish'); await new Promise(resolve => setTimeout(resolve, 2)) } }
await eventually(() => ready)
const blueFetch = { fetch(o) {
  if (gatewayClosed) { o.success({ code: 409, data: { error: { code: 'APP_PAGE_NOT_OPEN', message: '请在手机打开扩展程序' } } }); return }
  const path = new URL(o.url).pathname.split('/apps/' + link.appId)[1]
  const handler = routes.get((o.method || 'GET') + ' ' + path); assert.ok(handler)
  handler({ async json() { return JSON.parse(o.data) } }).then(o.success, () => o.fail('', 1))
} }
const sdkScript = fs.readFileSync(new URL('../watch/src/common/orbitv/index.js', import.meta.url), 'utf8').replace(/^import .*$/gm, '').replace('export function createOrbitVClient', 'function createOrbitVClient')
const factory = new Function('fetch', 'request', 'file', sdkScript + '\nreturn createOrbitVClient;')(blueFetch, {}, {})
let factoryCalls = 0
const createClient = config => { factoryCalls++; return factory(config) }
const homeScript = fs.readFileSync(new URL('../watch/src/Home/index.ux', import.meta.url), 'utf8').split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm, '').replace('export default', 'return')
const options = new Function('storage', 'dashboard', 'brightness', 'syncOrbitV', 'createOrbitVClient', 'createArchive', homeScript)(storage, dashboard, { setKeepScreenOn() {} }, syncOrbitV, createClient, createArchive)
const page = { ...options.data, runtime: {}, $element() { return { scrollTo() {} } } }
for (const [key, method] of Object.entries(options)) if (typeof method === 'function') page[key] = method.bind(page)
try {
  page.onInit(); page.onReady(); await eventually(() => page.runtime.loaded); await page.checkControl()
  await eventually(() => watchAppReady); assert.equal(watchVersion, '0.5.3')
  assert.equal(factoryCalls, 1); assert.equal(healthRequests, 0, 'opening page never fetches health data')
  assert.equal((await page.runtime.archive.read(yesterday)).revision, legacy.revision)
  holdIndex = true
  const initial = page.refresh(); await eventually(() => pendingIndex)
  assert.equal(confirmations, 0, 'ack must wait for committed index'); assert.equal((await page.runtime.archive.read(yesterday)).revision, legacy.revision)
  pendingIndex(); pendingIndex = null; await initial
  assert.equal(confirmations, 2); assert.equal(page.runtime.snapshot.revision, first.revision)
  assert.equal(page.runtime.archive.dates().length, 2); assert.ok(records.has('fitness.dashboard.v2'), 'legacy cache retained')
  const unchangedWrites = writes.length; await page.refresh(); assert.equal(writes.length, unchangedWrites, 'unchanged sync writes no health days or index')
  assert.equal(confirmations, 3)
  const changed = { ...first, revision: first.revision + 10, steps: 0 }; nativeDays.set(first.day, changed)
  const automaticRequests = healthRequests; await page.checkControl(); assert.equal(healthRequests, automaticRequests, 'record edits without command never sync')
  const yesterdayKey = JSON.parse(records.get('fitness.index.v3')).days[yesterday].key
  const dayWrites = () => writes.filter(key => key.startsWith('fitness.day.v3.')).length
  const beforeCommand = dayWrites(); command = 1; await page.checkControl()
  assert.equal(page.runtime.snapshot.steps, 0); assert.equal(page.runtime.archive.command(), 1)
  assert.equal(dayWrites(), beforeCommand + 1, 'only changed day written'); assert.equal(JSON.parse(records.get('fitness.index.v3')).days[yesterday].key, yesterdayKey)
  const afterCommand = healthRequests; await page.checkControl(); assert.equal(healthRequests, afterCommand, 'same phone command consumed once')
  failIndex = true; nativeDays.set(first.day, { ...changed, revision: first.revision + 20, steps: 123 })
  const beforeFailure = confirmations; await page.refresh(); assert.equal(confirmations, beforeFailure)
  assert.equal((await page.runtime.archive.read(first.day)).steps, 0, 'failed index commit preserves previous data')
  failIndex = false; await page.refresh(); assert.equal((await page.runtime.archive.read(first.day)).steps, 123)
  await assert.rejects(page.runtime.archive.merge([{ ...nativeDays.get(first.day), steps: 456 }]), /冲突/)
  await assert.rejects(page.runtime.archive.merge([first]), /较旧/)
  await assert.rejects(page.runtime.archive.merge([{ ...first, schema: 99 }]))
  await assert.rejects(page.runtime.archive.merge([changed, changed]), /较旧|重复/)
  const reloaded = createArchive(storage); await reloaded.load(); assert.equal((await reloaded.read(yesterday)).revision, nativeDays.get(yesterday).revision)
  page.select('meals'); assert.deepEqual(page.items.map(item => item.action), ['meal:breakfast', 'meal:lunch', 'meal:dinner'])
  assert.ok(page.items.every(item => !item.body.includes('燕麦'))); page.select('meal:breakfast'); assert.equal(page.items[0].title, '燕麦粥'); page.back(); assert.equal(page.runtime.page, 'meals')
  const positive = dashboard.rows(first, 'home').find(item => item.balance); assert.equal(positive.balance, '+500 kcal'); assert.equal(positive.balanceColor, '#19633F')
  const negative = dashboard.rows({ ...first, energy: { ...first.energy, intakeKcal: 2500 } }, 'home').find(item => item.balance); assert.equal(negative.balance, '−500 kcal'); assert.equal(negative.balanceColor, '#913643')
  assert.equal(dashboard.rows({ ...first, energy: { ...first.energy, intakeKcal: null } }, 'home').find(item => item.balance).balance, '待补充记录')
  assert.ok(dashboard.rows(first, 'advice').every(item => /^(吃饭|锻炼)建议/.test(item.title)))
  const bodyRows = dashboard.rows(first, 'home')
  const bmiScale = bodyRows.find(item => item.title.startsWith('BMI')).scale
  assert.equal(bmiScale.bands.length, 4); assert.equal(bmiScale.markerLeft, 127.5)
  const fatScale = bodyRows.find(item => item.title.startsWith('体脂率')).scale
  assert.equal(fatScale.bands.length, 3); assert.equal(fatScale.markerLeft, 107)
  assert.throws(() => dashboard.validate({ ...first, body: { ...first.body, fatScale: { ...first.body.fatScale, breaks: [20, 8] } } }))
  const oldBody = { ...first.body }; delete oldBody.bmiScale; delete oldBody.fatScale
  dashboard.validate({ ...first, body: oldBody })
  assert.match(dashboard.rows({ ...first, body: oldBody }, 'home').find(item => item.title.startsWith('BMI')).scaleNote, /尚未同步/)
  const offlineRequests = healthRequests; transportReady = false; await page.refresh()
  assert.equal(healthRequests, offlineRequests, 'disconnected Bluetooth never forwards health data'); assert.match(page.connectionText, /蓝牙/)
  await page.checkControl(); assert.equal(healthRequests, offlineRequests)
  transportReady = true; await page.checkControl(); assert.equal(healthRequests, offlineRequests, 'reconnect alone never syncs')
  const beforeDate = healthRequests; page.previousDay(); await page.loadDay(); assert.equal(page.runtime.snapshot.day, yesterday); assert.equal(healthRequests, beforeDate)
  page.select('day:' + first.day); await page.loadDay()
  gatewayClosed = true; await page.refresh(); assert.match(page.connectionText, /扩展程序/)
  gatewayClosed = false; nativeClosed = true; await page.refresh(); assert.match(page.connectionText, /持续连接/)
  nativeClosed = false; nativeDays.set(first.day, { ...first, revision: first.revision + 30 })
  holdIndex = true; const beforeHide = confirmations; const leaving = page.refresh(); await eventually(() => pendingIndex)
  page.onHide(); pendingIndex(); pendingIndex = null; await leaving; assert.equal(confirmations, beforeHide, 'hidden page never acknowledges transfer')
  const report = { syntheticOnly: true, physicalNewVersionVerified: false, previousVersionGt2SyncUserConfirmed: true,
    verified: ['SDK initialized without app globals', 'loopback-only pairing', 'production extension and SDK', 'legacy cache migration', 'paged history sync', 'storage before acknowledgment', 'unchanged sync writes nothing', 'record edits do not sync', 'explicit phone command consumed once', 'only changed day updated', 'history preserved', 'failed index commit preserves cache', 'zero steps', 'revision conflicts rejected', 'invalid schema rejected', 'offline date switching', 'meal summary then meal details', 'raw positive and negative energy difference', 'only food and exercise advice', 'BMI and body fat axes', 'axis marker positions', 'invalid axis boundaries rejected', 'legacy snapshots show missing axis hint', 'closed phone or extension error', 'Bluetooth preflight gates transfer', 'reconnection does not sync health', 'watch version handshake', 'hidden page no acknowledgment'], timestamp: new Date().toISOString() }
  fs.mkdirSync(new URL('../artifacts/', import.meta.url), { recursive: true }); fs.writeFileSync(new URL('../artifacts/watch-orbitv-simulation-0.5.3.json', import.meta.url), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
} finally { page.onDestroy() }
