// Connection metadata only. No real phone interface, Bluetooth, tokens or health records.
import fs from 'node:fs'
import assert from 'node:assert/strict'
import { validateLink, readControl, heartbeat, LINK_KEY } from '../orbitv/common/native-link.js'

const link = { schema: 1, appId: 'com.lrh.healthvivo.watch', baseUrl: 'http://127.0.0.1:28341', token: 'a'.repeat(64) }
let phoneUp = false, bluetooth = true, watchOpen = false, watchLookupFails = false, commands = 0, healthRequests = 0
let holdControl = false, releaseControl = null
const originalFetch = globalThis.fetch
globalThis.fetch = async (url, options) => {
  if (!phoneUp) throw new Error('synthetic offline phone')
  assert.equal(options.headers.Authorization, 'Bearer ' + link.token)
  const path = new URL(url).pathname
  if (path === '/sync') { healthRequests++; throw new Error('metadata monitor must never fetch health data') }
  if (holdControl && path === '/control') {
    holdControl = false; await new Promise(resolve => { releaseControl = resolve })
  }
  if (path === '/heartbeat') {
    const body = JSON.parse(options.body); assert.equal(body.transportReady, bluetooth && !watchLookupFails)
    if (!body.transportReady) watchOpen = false
  } else if (path === '/request') commands++
  else assert.equal(path, '/control')
  return { ok: true, json: async () => ({ requestId: commands, lastSavedAt: 0, transportReady: bluetooth,
    watchAppConnected: watchOpen, watchVersion: '0.5.3', message: '连接就绪' }) }
}
const ov = { storage: { get: async () => link }, watch: { getInfo: async () => {
  if (watchLookupFails) throw new Error('synthetic OrbitV lookup failure')
  return { connected: bluetooth, transportReady: bluetooth }
} } }
const script = fs.readFileSync(new URL('../orbitv/pages/index/index.vue', import.meta.url), 'utf8')
  .split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm, '').replace('export default', 'return')
const options = new Function('getOrbitV', 'validateLink', 'readControl', 'heartbeat', 'LINK_KEY', script)(async () => ov, validateLink, readControl, heartbeat, LINK_KEY)
const page = options.data()
for (const [key, method] of Object.entries(options.methods)) page[key] = method.bind(page)
try {
  await page.refresh()
  assert.equal(page.phoneReady, false); assert.equal(page.transportReady, true); assert.equal(page.watchReady, false)
  phoneUp = true; bluetooth = false; await page.refresh()
  assert.equal(page.phoneReady, true); assert.equal(page.transportReady, false)
  bluetooth = true; watchLookupFails = true; await page.refresh()
  assert.equal(page.phoneReady, true); assert.equal(page.transportReady, false)
  watchLookupFails = false; watchOpen = true; await page.refresh()
  assert.equal(page.phoneReady, true); assert.equal(page.transportReady, true); assert.equal(page.watchReady, true)
  assert.equal(commands, 0); assert.equal(healthRequests, 0, 'reconnect never sends health data or creates commands')
  bluetooth = false; await page.publish(); assert.equal(commands, 0, 'publish must recheck cached readiness')
  bluetooth = true; watchOpen = true; await page.refresh(); await page.publish(); assert.equal(commands, 1)
  holdControl = true; const pending = page.refresh()
  while (!releaseControl) await new Promise(resolve => setTimeout(resolve, 1))
  options.deactivated.call(page); releaseControl(); await pending
  assert.equal(page.phoneReady, false); assert.equal(page.transportReady, false); assert.equal(page.watchReady, false)
  const report = { syntheticOnly: true, realNetworkRequests: 0, healthRequests,
    verified: ['phone and watch checks independent', 'Bluetooth lookup errors clear readiness', 'automatic reconnection checks metadata only',
      'manual publish rechecks readiness', 'only manual publish creates update command', 'hidden page ignores late results'], timestamp: new Date().toISOString() }
  fs.writeFileSync(new URL('../artifacts/orbitv-1.2.0-connection-check.json', import.meta.url), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
} finally { options.beforeUnmount.call(page); globalThis.fetch = originalFetch }
