import fs from 'node:fs'
import path from 'node:path'
import sample from '../watch/simulator/sample.mjs'
const target = process.argv[2]
if (!target || !fs.existsSync(path.join(target, 'health-vivo-workspace.txt'))) throw new Error('Expected a marked Studio copy')
const simulation = path.join(target, 'src', 'simulation')
fs.mkdirSync(simulation, { recursive: true })
fs.writeFileSync(path.join(simulation, 'sample.js'), 'export default ' + JSON.stringify(sample()))
fs.writeFileSync(path.join(simulation, 'storage.js'), `
let cache = ''
export default {
  get(options) { setTimeout(() => options.success(cache || options.default), 10) },
  set(options) { setTimeout(() => { cache = options.value; options.success() }, 20) }
}
`)
fs.writeFileSync(path.join(simulation, 'ble.js'), `
import sample from './sample'
export default {
  open(hooks) {
    let closed = false, approved = false
    const sync = () => {
      if (closed) return
      if (!approved) { hooks.pairing('A13C90'); hooks.message('演示确认码 A13C90，点击允许体验同步'); return }
      hooks.message('演示数据接收中…')
      setTimeout(() => {
        if (closed) return
        hooks.snapshot(Object.assign({}, sample, { revision: Date.now(), generatedAt: Date.now() }),
          saved => hooks.message(saved ? '演示数据已保存 · 未访问真实蓝牙' : '演示保存失败'))
      }, 300)
    }
    const connection = {
      approve() { approved = true; hooks.pairing(''); sync() },
      reject() { hooks.pairing(''); hooks.message('演示连接已拒绝') },
      request() { sync() }, close() { closed = true }
    }
    setTimeout(sync, 100)
    return connection
  }
}
`)
const home = path.join(target, 'src', 'Home', 'index.ux')
fs.writeFileSync(home, fs.readFileSync(home, 'utf8')
  .replace("'@blueos.storage.storage'", "'../simulation/storage'")
  .replace("'../lib/ble-native'", "'../simulation/ble'")
  .replace('VITALITY · 活力记录', '演示数据 · 活力记录'))
// A preview must never package the private production BLE identity.
fs.writeFileSync(path.join(target, 'src', 'common', 'ble.local.js'), 'export default { privateKey: "" }\n')
const manifestPath = path.join(target, 'src', 'manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
manifest.package += '.preview'; manifest.name = '活力记录演示'
manifest.features = [{ name: 'blueos.storage.storage' }]; delete manifest.permissions
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2))
console.log('Synthetic BLE preview prepared. No user data, private identity, model API or real Bluetooth.')
