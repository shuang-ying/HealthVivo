// Mock only the native APIs in a marked, separate Studio preview; keep the production page and archive.
import fs from 'node:fs'
import path from 'node:path'
import sample from '../watch/simulator/sample.mjs'
const target = process.argv[2]
if (!target || !fs.existsSync(path.join(target, 'health-vivo-workspace.txt'))) throw new Error('Expected a marked Studio copy')
const simulation = path.join(target, 'src', 'simulation'); fs.mkdirSync(simulation, { recursive: true })
const first = sample(); first.energy.referenceKcal = 2000; first.energy.intakeKcal = 1500
const days = [0, 1, 2].map(offset => {
  const day = new Date(first.day + 'T12:00:00'); day.setDate(day.getDate() - offset)
  return { ...first, day: day.toISOString().slice(0, 10), revision: first.revision + offset }
})
fs.writeFileSync(path.join(simulation, 'days.js'), 'export default ' + JSON.stringify(days))
fs.writeFileSync(path.join(simulation, 'storage.js'), `
import days from './days'
const values = {}; const index = { schema: 3, command: 0, days: {} }
days.forEach(snapshot => { const key = 'fitness.day.v3.' + snapshot.day + '.' + snapshot.revision; index.days[snapshot.day] = { key, revision: snapshot.revision, savedAt: snapshot.generatedAt }; values[key] = JSON.stringify({ snapshot, savedAt: snapshot.generatedAt }) })
values['fitness.index.v3'] = JSON.stringify(index)
export default { get(o) { setTimeout(() => o.success(values[o.key] || o.default), 10) }, set(o) { setTimeout(() => { values[o.key] = o.value; o.success() }, 20) }, delete(o) { delete values[o.key]; o.success() } }
`)
fs.writeFileSync(path.join(simulation, 'client.js'), `
import days from './days'
export function createOrbitVClient() {
  return { http: { async request(path, options) {
    if (path === '/api/control') return { data: { requestId: 0 } }
    if (path === '/api/saved') return { data: { ok: true } }
    if (path === '/api/sync') { const request = JSON.parse(options.data); return { data: { schema: 3, sessionId: 'demo', receiptId: 'demo', command: 0, cursor: 0, more: false, days: days.filter(day => request.known[day.day] !== day.revision) } } }
    throw new Error('Unexpected demo path')
  } } }
}
`)
fs.writeFileSync(path.join(simulation, 'brightness.js'), 'export default { setKeepScreenOn() {} }\n')
const home = path.join(target, 'src', 'Home', 'index.ux')
fs.writeFileSync(home, fs.readFileSync(home, 'utf8').replace("'@blueos.storage.storage'", "'../simulation/storage'").replace("'@blueos.hardware.display.brightness'", "'../simulation/brightness'")
  .replace("'../common/orbitv/index.js'", "'../simulation/client'").replace('VITALITY · 活力记录', '演示数据 · 活力记录'))
fs.writeFileSync(path.join(target, 'src', 'common', 'ble.local.js'), 'export default { privateKey: "" }\n')
const manifestPath = path.join(target, 'src', 'manifest.json'); const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
manifest.package += '.preview'; manifest.name = '活力记录演示'; manifest.features = []; manifest.permissions = []
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2))
console.log('Synthetic OrbitV preview ready: three days, manual updates, no Bluetooth, API or user data.')
